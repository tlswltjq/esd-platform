#!/bin/sh
# Extract mysqlbinlog of exactly the server version shipped in the base MySQL image.
# The official image omits mysqlbinlog; Oracle Linux packages it with the full client,
# which conflicts with the image's own client files, so it is installed into a separate root.
set -eu

ROOT=/tmp/client-root
REPOS=/tmp/client-repos
mkdir -p "$ROOT/etc/pki" "$REPOS" /tmp/client-cache /out/usr/bin
cp -r /etc/pki/rpm-gpg "$ROOT/etc/pki/"
region=$(cat /etc/dnf/vars/ociregion)
domain=$(cat /etc/dnf/vars/ocidomain)
# microdnf does not expand repository variables for an alternate install root.
for repo in /etc/yum.repos.d/*.repo; do
    sed -e "s/\$ociregion/$region/g" -e "s/\$ocidomain/$domain/g" "$repo" > "$REPOS/$(basename "$repo")"
done

server_version=$(mysqld --version | sed -n 's/.* Ver \([0-9.]*\) .*/\1/p')
microdnf install -y --installroot="$ROOT" --config=/etc/dnf/dnf.conf --noplugins \
    --setopt=cachedir=/tmp/client-cache --setopt=reposdir="$REPOS" --setopt=varsdir=/etc/dnf/vars \
    --setopt=install_weak_deps=0 --releasever=9 --nodocs \
    --disablerepo=mysql8.0-server-minimal --disablerepo=mysql-tools-community \
    "mysql-$server_version"

cp "$ROOT/usr/bin/mysqlbinlog" /out/usr/bin/mysqlbinlog
client_version=$(/out/usr/bin/mysqlbinlog --version | sed -n 's/.* Ver \([0-9.]*\) .*/\1/p')
if [ "$client_version" != "$server_version" ]; then
    echo "mysqlbinlog $client_version does not match server $server_version" >&2
    exit 1
fi
if ldd /out/usr/bin/mysqlbinlog | grep -q "not found"; then
    echo "mysqlbinlog needs libraries missing from the base image" >&2
    exit 1
fi
echo "mysqlbinlog $client_version"
