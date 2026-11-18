# The bridge as a container, for any host that runs one (Fly.io, Render, Railway, Cloud Run, a VPS...).
#   docker build -t jarvis-bridge bridge && docker run -p 8787:8787 jarvis-bridge
FROM node:24-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
ENV PORT=8787
EXPOSE 8787
USER node
CMD ["node", "src/node.js"]
