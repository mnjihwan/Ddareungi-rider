# Web bundle carrier image (never run). Build context: Ddareungi-rider/web/dist after `npm run build`.
# deploy.sh extracts /site with `docker create` + `docker cp` into /srv/ddareungi/web/releases/<digest>
# and nginx serves it; using ECR keeps one artifact store, digest pinning and the lifecycle policy.
FROM scratch
COPY . /site/
LABEL org.opencontainers.image.title="ddareungi-rider-web-bundle"
CMD ["/noop"]
