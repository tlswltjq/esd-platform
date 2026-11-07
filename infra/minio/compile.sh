#!/bin/sh
set -eu

# 결과물은 끝날 때(성공·실패 모두) 마운트 폴더 /out의 주인에게 돌려준다.
# /out은 Gradle이 호출 사용자로 만든 폴더라, rootful Docker에서는 호출 사용자 uid,
# rootless Docker에서는 컨테이너 root(= 호출 사용자)가 되어 아무것도 바꾸지 않는다.
# 주인이 다른 파일만 바꾸고, 되돌리지 못해도 빌드 결과(종료 코드)는 그대로 둔다.
restore_ownership() {
    status=$?
    trap - EXIT
    owner=$(stat -c '%u:%g' /out 2>/dev/null || true)
    if [ -n "$owner" ]; then
        uid=${owner%:*}
        gid=${owner#*:}
        # Go 모듈 캐시는 원래 읽기 전용 권한이라, 주인이 맞아도 지울 수 있게 쓰기 권한을 준다.
        chmod -R u+w /go/pkg/mod 2>/dev/null || echo "경고: Go 모듈 캐시의 쓰기 권한을 되돌리지 못했다" >&2
        find /out /go/pkg/mod /root/.cache/go-build \( ! -user "$uid" -o ! -group "$gid" \) \
                -exec chown -h "$owner" {} + 2>/dev/null \
            || echo "경고: 빌드 결과물의 주인을 $owner(으)로 되돌리지 못했다" >&2
    fi
    exit "$status"
}
trap restore_ownership EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# 새로 받는 Go 모듈도 처음부터 쓰기 가능하게 둔다.
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-modcacherw"

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
