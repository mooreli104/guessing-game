# One image containing both halves of the game. The backend serves the built frontend as
# static files, so there is a single process on a single origin -- no CORS, and the browser
# derives the WebSocket URL (wss:// included) from the page it was served by.
#
# The ingest job is deliberately NOT part of this image. It needs Python, easyocr and
# ~2GB of torch weights, and it only runs offline against the database. Keeping it out
# means the deployed image is a JRE and a jar.

# --- Build the frontend ---------------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /frontend

# Copy the manifests alone first so `npm ci` is only re-run when dependencies change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build


# --- Build the backend, with the frontend bundled into its resources -------------------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src

COPY backend/ ./
# The frontend lands on the classpath at /public, which is where App.java looks for it.
COPY --from=frontend /frontend/dist ./app/src/main/resources/public

# gradlew is checked in without the executable bit (it was committed from Windows).
RUN chmod +x gradlew \
    && ./gradlew installDist --no-daemon -x test


# --- Runtime ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=backend /src/app/build/install/app ./

# The platform overrides PORT; App.java falls back to 7070 when it is unset.
ENV PORT=7070
EXPOSE 7070

CMD ["./bin/app"]
