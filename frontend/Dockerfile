FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build
EXPOSE 5173
# vite preview serves dist/ and proxies /api (incl. SSE) to BACKEND_URL
CMD ["npx", "vite", "preview", "--host", "--port", "5173"]
