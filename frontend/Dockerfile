FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG BUILD_ENV=local
ARG NEXT_PUBLIC_API_URL=
RUN if [ -n "${NEXT_PUBLIC_API_URL}" ]; then \
      export NEXT_PUBLIC_API_URL="${NEXT_PUBLIC_API_URL}"; \
    fi; \
    if [ "${BUILD_ENV}" != "local" ] && [ -f ".env.${BUILD_ENV}" ]; then \
      set -a; . ".env.${BUILD_ENV}"; set +a; \
    fi; \
    npm run build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
RUN chgrp -R 0 /app && chmod -R g=u /app
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
