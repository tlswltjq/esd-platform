# Build the pinned upstream sources when the public MinIO registry is unavailable.
FROM golang:1.24-alpine AS build
RUN apk add --no-cache git
ENV CGO_ENABLED=0
RUN git clone --depth 1 --branch RELEASE.2025-04-22T22-12-26Z https://github.com/minio/minio.git /src/minio
WORKDIR /src/minio
RUN go build -p 2 -trimpath -o /out/minio .
RUN git clone --depth 1 --branch RELEASE.2025-04-16T18-13-26Z https://github.com/minio/mc.git /src/mc
WORKDIR /src/mc
RUN go build -p 2 -trimpath -o /out/mc .

FROM alpine:3.21
RUN apk add --no-cache ca-certificates curl
COPY --from=build /out/minio /out/mc /usr/bin/
COPY --from=build /src/minio/LICENSE /licenses/minio-LICENSE
COPY --from=build /src/mc/LICENSE /licenses/mc-LICENSE
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
