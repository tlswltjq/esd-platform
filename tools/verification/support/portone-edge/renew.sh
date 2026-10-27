#!/bin/sh
set -eu

EDGE_ROOT=/opt/stove-portone-webhook
. "$EDGE_ROOT/images.env"

docker run --rm --network host \
    -v "$EDGE_ROOT/letsencrypt:/etc/letsencrypt" \
    -v "$EDGE_ROOT/acme:/var/www/acme" \
    -v "$EDGE_ROOT/certbot-work:/var/lib/letsencrypt" \
    -v "$EDGE_ROOT/certbot-logs:/var/log/letsencrypt" \
    "$CERTBOT_IMAGE" renew --quiet --no-random-sleep-on-renew "$@"
docker exec stove-portone-edge nginx -t
docker exec stove-portone-edge nginx -s reload
