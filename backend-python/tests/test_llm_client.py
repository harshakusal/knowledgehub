import json

import httpx
import pytest
import respx

from app.clients.llm_client import CircuitBreaker, LlmClient, parse_retry_after
from app.config import LlmSettings
from app.errors import LlmClientError, LlmUnavailable

BASE = "http://llm.test"
EMBED_OK = {"data": [{"index": 0, "embedding": [0.1, 0.2, 0.3]}]}
CHAT_OK = {"choices": [{"message": {"content": "hello"}}]}


def make(min_calls=20, api_key="secret-key"):
    sleeps: list[float] = []

    async def sleep(d):
        sleeps.append(d)

    s = LlmSettings(base_url=BASE, api_key=api_key, embedding_dim=3, connect_timeout=0.5, read_timeout=0.3,
                    initial_backoff=0.1, max_backoff=2, breaker_min_calls=min_calls, breaker_window=min_calls)
    return LlmClient(s, sleep=sleep), sleeps


@respx.mock
async def test_retries_429_honouring_retry_after_then_succeeds():
    route = respx.post(f"{BASE}/v1/embeddings").mock(side_effect=[
        httpx.Response(429, headers={"Retry-After": "2"}), httpx.Response(200, json=EMBED_OK)])
    client, sleeps = make()
    assert await client.embed(["x"]) == [[0.1, 0.2, 0.3]]
    assert route.call_count == 2
    assert len(sleeps) == 1 and sleeps[0] >= 2


@respx.mock
async def test_retries_503_max_four_attempts_with_exponential_backoff():
    route = respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(503))
    client, sleeps = make()
    with pytest.raises(LlmUnavailable, match="busy or unreachable"):
        await client.complete([{"role": "user", "content": "q"}])
    assert route.call_count == 4
    assert len(sleeps) == 3
    assert 0.05 <= sleeps[0] <= 0.1 and 0.1 <= sleeps[1] <= 0.2 and 0.2 <= sleeps[2] <= 0.4


@pytest.mark.parametrize("code", [400, 401, 404, 422])
@respx.mock
async def test_never_retries_client_errors(code):
    route = respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(code))
    client, sleeps = make()
    with pytest.raises(LlmClientError):
        await client.complete([{"role": "user", "content": "q"}])
    assert route.call_count == 1 and sleeps == []


@respx.mock
async def test_retries_read_and_connect_timeouts():
    route = respx.post(f"{BASE}/v1/chat").mock(side_effect=[
        httpx.ReadTimeout("slow"), httpx.ConnectTimeout("down"), httpx.Response(200, json=CHAT_OK)])
    client, sleeps = make()
    assert await client.complete([{"role": "user", "content": "q"}]) == "hello"
    assert route.call_count == 3 and len(sleeps) == 2


@respx.mock
async def test_gives_up_when_retry_after_exceeds_cap():
    route = respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(429, headers={"Retry-After": "120"}))
    client, _ = make()
    with pytest.raises(LlmUnavailable, match="wait too long"):
        await client.complete([{"role": "user", "content": "q"}])
    assert route.call_count == 1


@respx.mock
async def test_circuit_opens_and_fails_fast_without_calling_server():
    route = respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(503))
    client, _ = make(min_calls=4)
    with pytest.raises(LlmUnavailable):
        await client.complete([{"role": "user", "content": "q"}])
    assert client.breaker.state == CircuitBreaker.OPEN
    calls = route.call_count
    with pytest.raises(LlmUnavailable, match="circuit open"):
        await client.complete([{"role": "user", "content": "q"}])
    assert route.call_count == calls  # no request made while open


@respx.mock
async def test_client_errors_do_not_open_circuit():
    respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(400))
    client, _ = make(min_calls=4)
    for _ in range(6):
        with pytest.raises(LlmClientError):
            await client.complete([{"role": "user", "content": "q"}])
    assert client.breaker.state == CircuitBreaker.CLOSED


def test_breaker_half_open_recovers_and_reopens():
    now = [0.0]
    b = CircuitBreaker(0.5, 2, 2, 10, clock=lambda: now[0])
    b.on_failure(); b.on_failure()
    assert b.state == b.OPEN
    now[0] = 11
    b.before_call()
    assert b.state == b.HALF_OPEN
    b.on_failure()
    assert b.state == b.OPEN
    now[0] = 22
    b.before_call(); b.on_success()
    assert b.state == b.CLOSED


@respx.mock
async def test_sends_bearer_key_and_never_exposes_it():
    route = respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(200, json=CHAT_OK))
    client, _ = make()
    await client.complete([{"role": "user", "content": "q"}], json_mode=True)
    req = route.calls.last.request
    assert req.headers["Authorization"] == "Bearer secret-key"
    assert json.loads(req.content)["response_format"] == {"type": "json_object"}
    assert "secret-key" not in repr(client.s)


@respx.mock
async def test_stream_tokens_until_done():
    sse = ('data: {"choices":[{"delta":{"content":"Hel"}}]}\n\n'
           'data: {"choices":[{"delta":{"content":"lo"}}]}\n\ndata: [DONE]\n\n')
    respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(200, text=sse, headers={"Content-Type": "text/event-stream"}))
    client, _ = make()

    async def never():
        return False

    assert "".join([t async for t in client.stream_chat([], never)]) == "Hello"


@respx.mock
async def test_stream_stops_reading_when_cancelled():
    sse = 'data: {"choices":[{"delta":{"content":"a"}}]}\n\n' * 50 + "data: [DONE]\n\n"
    respx.post(f"{BASE}/v1/chat").mock(return_value=httpx.Response(200, text=sse))
    client, _ = make()
    got: list[str] = []

    async def cancelled():
        return len(got) >= 3

    async for t in client.stream_chat([], cancelled):
        got.append(t)
    assert len(got) == 3


@respx.mock
async def test_stream_retries_before_first_token():
    sse = 'data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n'
    route = respx.post(f"{BASE}/v1/chat").mock(side_effect=[httpx.Response(429, headers={"Retry-After": "1"}),
                                                            httpx.Response(200, text=sse)])
    client, sleeps = make()

    async def never():
        return False

    assert [t async for t in client.stream_chat([], never)] == ["ok"]
    assert route.call_count == 2 and sleeps[0] >= 1


def test_parse_retry_after():
    assert parse_retry_after("3") == 3
    assert parse_retry_after(None) is None
    assert parse_retry_after("Wed, 21 Oct 2015 07:28:00 GMT") is None


async def test_cancelled_call_does_not_wedge_half_open_breaker():
    import asyncio
    now = [0.0]
    b = CircuitBreaker(0.5, 2, 2, 10, clock=lambda: now[0])
    b.on_failure(); b.on_failure()
    now[0] = 11
    client, _ = make()
    client.breaker = b

    async def slow():
        await asyncio.sleep(10)

    task = asyncio.create_task(client._execute("x", slow))
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    b.before_call()  # trial slot was released, so a new trial is allowed
    assert b.state == b.HALF_OPEN
