"""Start the KnowledgeHub API. Put this file in backend-python/ and run: python run.py
On Windows, psycopg async needs the Selector event loop (not the default Proactor)."""
import asyncio
import sys

import uvicorn

if __name__ == "__main__":
    server = uvicorn.Server(uvicorn.Config("app.main:app", host="127.0.0.1", port=8000))
    if sys.platform == "win32":
        asyncio.run(server.serve(), loop_factory=asyncio.SelectorEventLoop)
    else:
        asyncio.run(server.serve())
