#!/bin/sh
set -eu

# 기존 로컬 이미지와 같은 릴리즈. 태그가 가리키던 실제 커밋을 고정한다.
MINIO_COMMIT=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e
MC_COMMIT=b00526b153a31b36767991a4f5ce2cced435ee8e
export CGO_ENABLED=0 GOOS=linux

apk add --no-cache git
mkdir -p /out/usr/bin /out/licenses /out/etc/ssl/certs

build_binary() {
    project_name=$1
    source_commit=$2
    source_dir="/tmp/$project_name"
    git init "$source_dir"
    git -C "$source_dir" remote add origin "https://github.com/minio/$project_name.git"
    git -C "$source_dir" fetch --depth=1 origin "$source_commit"
    git -C "$source_dir" checkout --detach FETCH_HEAD
    test "$(git -C "$source_dir" rev-parse HEAD)" = "$source_commit"
    (cd "$source_dir" && go build -p 2 -trimpath -buildvcs=false -o "/out/usr/bin/$project_name" .)
    cp "$source_dir/LICENSE" "/out/licenses/$project_name-LICENSE"
}

build_binary minio "$MINIO_COMMIT"
build_binary mc "$MC_COMMIT"
cp /etc/ssl/certs/ca-certificates.crt /out/etc/ssl/certs/
