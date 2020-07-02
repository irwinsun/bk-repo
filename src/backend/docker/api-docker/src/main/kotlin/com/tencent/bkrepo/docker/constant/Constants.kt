package com.tencent.bkrepo.docker.constant

const val REPO_TYPE = "DOCKER"

const val BLOB_PATTERN = "/blobs"

const val MANIFEST_PATTERN = "/manifests"

const val EGOTIST = ""

const val USER_API_PREFIX = "/api"
const val DOCKER_API_PREFIX = "/v2"
const val DOCKER_API_SUFFIX = "/auth"

const val DOCKER_BLOB_SUFFIX = "{projectId}/{repoName}/**/blobs/uploads"
const val DOCKER_BLOB_UUID_SUFFIX = "{projectId}/{repoName}/**/blobs/uploads/{uuid}"
const val DOCKER_BLOB_DIGEST_SUFFIX = "{projectId}/{repoName}/**/blobs/{digest}"

const val DOCKER_MANIFEST_TAG_SUFFIX = "/{projectId}/{repoName}/**/manifests/{tag}"
const val DOCKER_MANIFEST_REFERENCE_SUFFIX = "/{projectId}/{repoName}/**/manifests/{reference}"

const val DOCKER_USER_MANIFEST_SUFFIX = "/manifest/{projectId}/{repoName}/**/{tag}"
const val DOCKER_USER_LAYER_SUFFIX = "/layer/{projectId}/{repoName}/**/{id}"
const val DOCKER_USER_REPO_SUFFIX = "/repo/{projectId}/{repoName}"
const val DOCKER_USER_TAG_SUFFIX = "/repo/tag/{projectId}/{repoName}/**"

const val DOCKER_TAGS_SUFFIX = "/{projectId}/{repoName}/{name}/tags/list"
const val DOCKER_CATALOG_SUFFIX = "_catalog"

const val HTTP_FORWARDED_PROTO = "X-Forwarded-Proto"
const val HTTP_PROTOCOL_HTTP = "http"
const val HTTP_PROTOCOL_HTTPS = "https"

const val AUTH_ENABLE = "enable"
const val AUTH_DISABLE = "disable"

const val REGISTRY_SERVICE = "bkrepo"

const val ERROR_MESSAGE = "{\"errors\":[{\"code\":\"%s\",\"message\":\"%s\",\"detail\":{%s}}]}"
const val ERROR_MESSAGE_EMPTY = "{\"errors\":[{\"code\":\"%s\",\"message\":\"%s\",\"detail\":null}]}"
const val AUTH_CHALLENGE = "Bearer realm=\"%s\",service=\"%s\""
const val AUTH_CHALLENGE_SERVICE_SCOPE = "Bearer realm=\"%s\",service=\"%s\",scope=\"%s\""
const val AUTH_CHALLENGE_SCOPE = ",scope=\"%s:%s:%s\""
const val AUTH_CHALLENGE_TOKEN = "{\"token\": \"%s\", \"access_token\": \"%s\",\"issued_at\": \"%s\"}"

const val DOCKER_HEADER_API_VERSION = "Docker-Distribution-Api-Version"
const val DOCKER_API_VERSION = "registry/2.0"
const val DOCKER_CONTENT_DIGEST = "Docker-Content-Digest"
