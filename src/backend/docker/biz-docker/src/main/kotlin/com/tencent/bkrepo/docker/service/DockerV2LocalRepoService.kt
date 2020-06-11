package com.tencent.bkrepo.docker.service

import com.fasterxml.jackson.databind.JsonNode
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.exception.PermissionCheckException
import com.tencent.bkrepo.docker.artifact.Artifact
import com.tencent.bkrepo.docker.artifact.DockerArtifactService
import com.tencent.bkrepo.docker.constant.EMPTYSTR
import com.tencent.bkrepo.docker.context.DownloadContext
import com.tencent.bkrepo.docker.context.RequestContext
import com.tencent.bkrepo.docker.context.UploadContext
import com.tencent.bkrepo.docker.errors.DockerV2Errors
import com.tencent.bkrepo.docker.exception.DockerFileSaveFailedException
import com.tencent.bkrepo.docker.exception.DockerNotFoundException
import com.tencent.bkrepo.docker.exception.DockerRepoNotFoundException
import com.tencent.bkrepo.docker.exception.DockerSyncManifestException
import com.tencent.bkrepo.docker.helpers.DockerCatalogTagsSlicer
import com.tencent.bkrepo.docker.helpers.DockerManifestDigester
import com.tencent.bkrepo.docker.helpers.DockerManifestSyncer
import com.tencent.bkrepo.docker.helpers.DockerPaginationElementsHolder
import com.tencent.bkrepo.docker.helpers.DockerSearchBlobPolicy
import com.tencent.bkrepo.docker.manifest.ManifestDeserializer
import com.tencent.bkrepo.docker.manifest.ManifestListSchema2Deserializer
import com.tencent.bkrepo.docker.manifest.ManifestType
import com.tencent.bkrepo.docker.model.DockerBlobInfo
import com.tencent.bkrepo.docker.model.DockerDigest
import com.tencent.bkrepo.docker.model.ManifestMetadata
import com.tencent.bkrepo.docker.response.CatalogResponse
import com.tencent.bkrepo.docker.response.TagsResponse
import com.tencent.bkrepo.docker.util.DockerSchemaUtil
import com.tencent.bkrepo.docker.util.DockerUtil
import com.tencent.bkrepo.docker.util.JsonUtil
import com.tencent.bkrepo.docker.util.RepoServiceUtil
import com.tencent.bkrepo.docker.util.RepoUtil
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.StringUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import javax.ws.rs.core.UriBuilder

@Service
class DockerV2LocalRepoService @Autowired constructor(val repo: DockerArtifactService) : DockerV2RepoService {

    var httpHeaders: HttpHeaders = HttpHeaders()
    lateinit var userId: String
    val manifestSyncer = DockerManifestSyncer(repo)

    companion object {
        private val logger = LoggerFactory.getLogger(DockerV2LocalRepoService::class.java)
    }

    override fun ping(): ResponseEntity<Any> {
        return ResponseEntity.ok().header("Content-Type", "application/json")
            .header("Docker-Distribution-Api-Version", "registry/2.0").body("{}")
    }

    override fun getTags(pathContext: RequestContext, maxEntries: Int, lastEntry: String): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        val elementsHolder = DockerPaginationElementsHolder()
        val manifests = repo.findArtifacts(pathContext.projectId, pathContext.repoName, "manifest.json")

        if (manifests.isNotEmpty()) {
            manifests.forEach {
                var path = it["path"] as String
                val tagName =
                    path.replaceAfterLast("/", "").removeSuffix("/").removePrefix("/" + pathContext.dockerRepo + "/")
                elementsHolder.elements.add(tagName)
            }

            if (elementsHolder.elements.isEmpty()) {
                return DockerV2Errors.nameUnknown(pathContext.dockerRepo)
            } else {
                DockerCatalogTagsSlicer.sliceCatalog(elementsHolder, maxEntries, lastEntry)
                val shouldAddLinkHeader = elementsHolder.hasMoreElements
                val tagsResponse = TagsResponse(elementsHolder, pathContext.dockerRepo)
                httpHeaders.set("Docker-Distribution-Api-Version", "registry/2.0")
                if (shouldAddLinkHeader) {
                    httpHeaders.set(
                        "Link",
                        "</v2/" + pathContext.dockerRepo + "/tags/list?last=" + tagsResponse.tags.last() as String + "&n=" + maxEntries + ">; rel=\"next\""
                    )
                }

                return ResponseEntity(tagsResponse, httpHeaders, HttpStatus.OK)
            }
        } else {
            return DockerV2Errors.nameUnknown(pathContext.dockerRepo)
        }
    }

    override fun catalog(projectId: String, name: String, maxEntries: Int, lastEntry: String): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, projectId, name)
        val manifests = repo.findArtifacts(projectId, name, "manifest.json")
        val elementsHolder = DockerPaginationElementsHolder()

        manifests.forEach {
            val path = it["path"] as String
            val repoName = path.replaceAfterLast("/", "").replaceAfterLast("/", "").removeSuffix("/")

            if (StringUtils.isNotBlank(repoName)) {
                elementsHolder.addElement(repoName)
            }
            DockerCatalogTagsSlicer.sliceCatalog(elementsHolder, maxEntries, lastEntry)
        }
        val shouldAddLinkHeader = elementsHolder.hasMoreElements
        val catalogResponse = CatalogResponse(elementsHolder)
        httpHeaders.set("Docker-Distribution-Api-Version", "registry/2.0")
        if (shouldAddLinkHeader) {
            httpHeaders.set(
                "Link",
                "</v2/_catalog?last=" + catalogResponse.repositories.last() as String + "&n=" + maxEntries + ">; rel=\"next\""
            )
        }
        return ResponseEntity(catalogResponse, httpHeaders, HttpStatus.OK)
    }

    override fun getManifest(pathContext: RequestContext, reference: String): ResponseEntity<Any> {
        logger.info("get manifest params [$pathContext] , [$reference] ")
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        return try {
            val digest = DockerDigest(reference)
            getManifestByDigest(pathContext, digest)
        } catch (exception: Exception) {
            logger.trace("unable to parse digest, fetching manifest by tag '{}'", reference)
            getManifestByTag(pathContext, reference)
        }
    }

    private fun getManifestByDigest(pathContext: RequestContext, digest: DockerDigest): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        logger.info("fetch docker manifest [${pathContext.dockerRepo}] and digest [$digest] in repo [${pathContext.repoName}]")
        var matched = findMatchArtifact(pathContext, "manifest.json")
        if (matched == null) {
            val acceptable = RepoServiceUtil.getAcceptableManifestTypes(httpHeaders)
            if (acceptable.contains(ManifestType.Schema2List)) {
                matched = findMatchArtifact(pathContext, "list.manifest.json")
            }
        }

        return if (matched == null) {
            DockerV2Errors.manifestUnknown(digest.toString())
        } else {
            buildManifestResponse(pathContext, pathContext.dockerRepo, digest, matched.length)
        }
    }

    private fun findMatchArtifact(pathContext: RequestContext, filename: String): Artifact? {
        val nodeDetail = repo.findArtifact(pathContext, filename) ?: run {
            return null
        }
        val sha256 = nodeDetail.nodeInfo.sha256
        return Artifact(pathContext.projectId, pathContext.repoName, pathContext.dockerRepo).sha256(sha256.toString())
            .length(nodeDetail.nodeInfo.size)
    }

    private fun getManifestByTag(pathContext: RequestContext, tag: String): ResponseEntity<Any> {
        val useManifestType = chooseManifestType(pathContext, tag)
        val manifestPath = buildManifestPathFromType(pathContext.dockerRepo, tag, useManifestType)
        logger.info("get manifest by tag params [$pathContext] ,[$manifestPath]")
        if (!repo.canRead(pathContext)) {
            return DockerV2Errors.unauthorizedManifest(manifestPath, null as String?)
        } else if (!repo.exists(pathContext.projectId, pathContext.repoName, manifestPath)) {
            return DockerV2Errors.manifestUnknown(manifestPath)
        } else {
            val manifest = repo.findManifest(pathContext.projectId, pathContext.repoName, manifestPath) ?: run {
                return DockerV2Errors.manifestUnknown(manifestPath)
            }
            logger.debug("get manifest by tag result [$manifest]")
            return buildManifestResponse(
                pathContext,
                manifestPath,
                DockerDigest("sh256:${manifest.nodeInfo.sha256}"),
                manifest.nodeInfo.size
            )
        }
    }

    private fun chooseManifestType(pathContext: RequestContext, tag: String): ManifestType {
        val acceptable = RepoServiceUtil.getAcceptableManifestTypes(httpHeaders)
        if (acceptable.contains(ManifestType.Schema2List)) {
            val manifestPath = buildManifestPathFromType(pathContext.dockerRepo, tag, ManifestType.Schema2List)
            if (repo.exists(pathContext.projectId, pathContext.repoName, manifestPath)) {
                return ManifestType.Schema2List
            }
        }

        return if (acceptable.contains(ManifestType.Schema2)) {
            ManifestType.Schema2
        } else if (acceptable.contains(ManifestType.Schema1Signed)) {
            ManifestType.Schema1Signed
        } else {
            if (acceptable.contains(ManifestType.Schema1)) ManifestType.Schema1 else ManifestType.Schema1Signed
        }
    }

    fun getManifestString(pathContext: RequestContext, tag: String): String {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        val useManifestType = chooseManifestType(pathContext, tag)
        val manifestPath = buildManifestPathFromType(pathContext.dockerRepo, tag, useManifestType)
        val manifest = repo.findManifest(pathContext.projectId, pathContext.repoName, manifestPath) ?: run {
            logger.info("node not exist [$pathContext]")
            return EMPTYSTR
        }
        val context =
            DownloadContext(pathContext.projectId, pathContext.repoName, pathContext.dockerRepo)
                    .sha256(manifest.nodeInfo.sha256!!).length(manifest.nodeInfo.size)
        val inputStream = repo.download(context)
        return inputStream.readBytes().toString(Charset.defaultCharset())
    }

    fun getRepoList(projectId: String, repoName: String): List<String> {
        RepoUtil.loadRepo(repo, userId, projectId, repoName)
        return repo.findRepoList(projectId, repoName)
    }

    fun getRepoTagList(projectId: String, repoName: String, image: String): Map<String, String> {
        return repo.findRepoTagList(projectId, repoName, image)
    }

    fun buildLayerResponse(pathContext: RequestContext, id: String): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        val digest = DockerDigest(id)
        val artifact = repo.findArtifactsByDigest(pathContext.projectId, pathContext.repoName, digest.filename())
        if (artifact.isEmpty()) {
            logger.warn("user [$userId]  get artifact  [$pathContext] faile: [$id] not found")
            throw DockerRepoNotFoundException(id)
        }
        logger.info("get blob info [$artifact]")
        val length = artifact[0]["size"] as Int
        val context = DownloadContext(
            pathContext.projectId,
            pathContext.repoName,
            pathContext.dockerRepo
        ).sha256(digest.getDigestHex()).length(length.toLong())
        val inputStreamResource = InputStreamResource(repo.download(context))
        httpHeaders.set("Docker-Distribution-Api-Version", "registry/2.0")
        httpHeaders.set("Docker-Content-Digest", digest.toString())
        httpHeaders.set(
            "Content-Type",
            DockerSchemaUtil.getManifestType(
                pathContext.projectId,
                pathContext.repoName,
                pathContext.dockerRepo,
                repo
            )
        )
        return ResponseEntity.ok().headers(httpHeaders).contentLength(context.length).body(inputStreamResource)
    }

    private fun buildManifestResponse(
        pathContext: RequestContext,
        manifestPath: String,
        digest: DockerDigest,
        length: Long
    ): ResponseEntity<Any> {
        val context = DownloadContext(
            pathContext.projectId,
            pathContext.repoName,
            pathContext.dockerRepo
        ).length(length).sha256(digest.getDigestHex())
        val inputStream = repo.download(context)
        val inputStreamResource = InputStreamResource(inputStream)
        val contentType =
            DockerSchemaUtil.getManifestType(pathContext.projectId, pathContext.repoName, manifestPath, repo)
        httpHeaders.set("Docker-Distribution-Api-Version", "registry/2.0")
        httpHeaders.set("Docker-Content-Digest", digest.toString())
        httpHeaders.set("Content-Type", contentType)
        logger.info("file [${digest.getDigestHex()}] result length [$length] type [$contentType]")
        return ResponseEntity.ok()
            .headers(httpHeaders)
            .contentLength(length)
            .body(inputStreamResource)
    }

    override fun deleteManifest(pathContext: RequestContext, reference: String): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        return try {
            deleteManifestByDigest(pathContext, DockerDigest(reference))
        } catch (exception: Exception) {
            logger.error("unable to parse digest, delete manifest by tag [$reference]")
            deleteManifestByTag(pathContext, reference)
        }
    }

    private fun deleteManifestByDigest(pathContext: RequestContext, digest: DockerDigest): ResponseEntity<Any> {
        logger.info("delete docker manifest for  [${pathContext.dockerRepo}] digest [$digest] in repo [${pathContext.repoName}]")
        val manifests = repo.findArtifacts(pathContext.projectId, pathContext.repoName, "manifest.json")
        val manifestIter = manifests.iterator()

        while (manifestIter.hasNext()) {
//            val manifest = manifestIter.next()
//            if (repo.canWrite(manifest.)) {
//                val manifestDigest = repo.getAttribute(projectId ,repoName ,manifest.path, digest.getDigestAlg())
//                if (StringUtils.isNotBlank(manifestDigest) && StringUtils.equals(manifestDigest, digest.getDigestHex()) && repo.delete(manifest.path)) {
//                    return ResponseEntity.status(202).header("Docker-Distribution-Api-Version", "registry/2.0").build()
//                }
//            }
        }

        return DockerV2Errors.manifestUnknown(digest.toString())
    }

    private fun deleteManifestByTag(pathContext: RequestContext, tag: String): ResponseEntity<Any> {
        val tagPath = "${pathContext.dockerRepo}/$tag"
        val manifestPath = "$tagPath/manifest.json"
        if (!repo.exists(pathContext.projectId, pathContext.repoName, manifestPath)) {
            return DockerV2Errors.manifestUnknown(manifestPath)
        } else if (repo.delete(tagPath)) {
            return ResponseEntity.status(202).header("Docker-Distribution-Api-Version", "registry/2.0").build()
        } else {
            logger.warn("unable to delete tag [$manifestPath]")
            return DockerV2Errors.manifestUnknown(manifestPath)
        }
    }

    override fun uploadManifest(
        pathContext: RequestContext,
        tag: String,
        mediaType: String,
        artifactFile: ArtifactFile
    ): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        if (!repo.canWrite(pathContext)) {
            return DockerV2Errors.unauthorizedUpload()
        }
        val stream = artifactFile.getInputStream()
        logger.info("deploy docker manifest [${pathContext.dockerRepo},$tag] into repo [${pathContext.repoName}] ,media [$mediaType]")
        val manifestType = ManifestType.from(mediaType)
        val manifestPath = buildManifestPathFromType(pathContext.dockerRepo, tag, manifestType)
        logger.info("upload manifest path {}", manifestPath)
        stream.use {
            val digest = processUploadedManifestType(
                pathContext,
                tag,
                manifestPath,
                manifestType,
                it,
                artifactFile
            )
            return ResponseEntity.status(201).header("Docker-Distribution-Api-Version", "registry/2.0")
                .header("Docker-Content-Digest", digest.toString()).build()
        }
    }

    private fun buildManifestPathFromType(dockerRepo: String, tag: String, manifestType: ManifestType): String {
        return if (ManifestType.Schema2List == manifestType) {
            "/$dockerRepo/$tag/list.manifest.json"
        } else {
            "/$dockerRepo/$tag/manifest.json"
        }
    }

    private fun processUploadedManifestType(
        pathContext: RequestContext,
        tag: String,
        manifestPath: String,
        manifestType: ManifestType,
        stream: InputStream,
        artifactFile: ArtifactFile
    ): DockerDigest {
        val manifestBytes = IOUtils.toByteArray(stream)
        val digest = DockerManifestDigester.calc(manifestBytes)
        logger.info("manifest file digest content digest : [$digest] ")
        if (ManifestType.Schema2List == manifestType) {
            processManifestList(pathContext, tag, manifestPath, digest!!, manifestBytes, manifestType)
            return digest
        } else {
            val manifestMetadata =
                ManifestDeserializer.deserialize(repo, pathContext, tag, manifestType, manifestBytes, digest!!)
            addManifestsBlobs(manifestType, manifestBytes, manifestMetadata)
            if (!manifestSyncer.sync(manifestMetadata, pathContext, tag)) {
                val msg = "fail to  sync manifest blobs, canceling manifest upload"
                logger.error(msg)
                throw DockerSyncManifestException(msg)
            } else {
                logger.info("start to upload manifest : {}", manifestType.toString())
                val response = repo.upload(
                    manifestUploadContext(
                        pathContext.projectId,
                        pathContext.repoName,
                        manifestType,
                        manifestMetadata,
                        manifestPath,
                        manifestBytes,
                        artifactFile
                    )
                )
                if (response) {
                    val params = buildManifestPropertyMap(pathContext.dockerRepo, tag, digest, manifestType)
                    val labels = manifestMetadata.tagInfo.labels
                    labels.entries().forEach {
                        params[it.key] = it.value
                    }
                    repo.setAttributes(pathContext.projectId, pathContext.repoName, manifestPath, params)
                    return digest
                } else {
                    throw DockerFileSaveFailedException(manifestPath)
                }
            }
        }
    }

    private fun processManifestList(
        pathContext: RequestContext,
        tag: String,
        manifestPath: String,
        digest: DockerDigest,
        manifestBytes: ByteArray,
        manifestType: ManifestType
    ) {
        val manifestList = ManifestListSchema2Deserializer.deserialize(manifestBytes)
        if (manifestList != null) {
            val iter = manifestList.manifests.iterator()
            // check every manifest in the repo
            while (iter.hasNext()) {
                val manifest = iter.next()
                val mDigest = manifest.digest
                val manifestFilename = DockerDigest(mDigest!!).filename()
                DockerUtil.findBlobGlobally(
                        repo,
                        pathContext,
                        manifestFilename
                    ) ?: run {
                        throw DockerNotFoundException("manifest list ($digest) miss manifest digest $mDigest. ==>$manifest")
                    }
            }
        }

        val response = repo.upload(
            manifestListUploadContext(
                pathContext.projectId,
                pathContext.repoName,
                manifestType,
                digest,
                manifestPath,
                manifestBytes
            )
        )
        if (response) {
            val params = buildManifestPropertyMap(pathContext.dockerRepo, tag, digest, manifestType)
            repo.setAttributes(pathContext.projectId, pathContext.repoName, manifestPath, params)
        } else {
            throw DockerFileSaveFailedException(manifestPath)
        }
    }

    private fun manifestListUploadContext(
        projectId: String,
        repoName: String,
        manifestType: ManifestType,
        digest: DockerDigest,
        manifestPath: String,
        manifestBytes: ByteArray
    ): UploadContext {
        val context = UploadContext(projectId, repoName, manifestPath).content(ByteArrayInputStream(manifestBytes))
        if (manifestType == ManifestType.Schema2List && "sha256" == digest.getDigestAlg()) {
            context.sha256(digest.getDigestHex())
        }

        return context
    }

    private fun buildManifestPropertyMap(
        dockerRepo: String,
        tag: String,
        digest: DockerDigest,
        manifestType: ManifestType
    ): HashMap<String, String> {
        var map = HashMap<String, String>()
        map[digest.getDigestAlg()] = digest.getDigestHex()
        map["docker.manifest.digest"] = digest.toString()
        map["docker.manifest"] = tag
        map["docker.repoName"] = dockerRepo
        map["docker.manifest.type"] = manifestType.toString()
        return map
    }

    private fun addManifestsBlobs(
        manifestType: ManifestType,
        manifestBytes: ByteArray,
        manifestMetadata: ManifestMetadata
    ) {
        if (ManifestType.Schema2 == manifestType) {
            addSchema2Blob(manifestBytes, manifestMetadata)
        } else if (ManifestType.Schema2List == manifestType) {
            addSchema2ListBlobs(manifestBytes, manifestMetadata)
        }
    }

    private fun addSchema2Blob(manifestBytes: ByteArray, manifestMetadata: ManifestMetadata) {
        val manifest = JsonUtil.readTree(manifestBytes)
        val config = manifest.get("config")
        if (config != null) {
            val digest = config.get("digest").asText()
            val blobInfo = DockerBlobInfo("", digest, 0L, "")
            manifestMetadata.blobsInfo.add(blobInfo)
        }
    }

    private fun addSchema2ListBlobs(manifestBytes: ByteArray, manifestMetadata: ManifestMetadata) {
        val manifestList = JsonUtil.readTree(manifestBytes)
        val manifests = manifestList.get("manifests")
        val manifest = manifests.iterator()

        while (manifest.hasNext()) {
            val manifestNode = manifest.next() as JsonNode
            val digest = manifestNode.get("platform").get("digest").asText()
            val dockerBlobInfo = DockerBlobInfo("", digest, 0L, "")
            manifestMetadata.blobsInfo.add(dockerBlobInfo)
            val manifestFilename = DockerDigest(digest).filename()
            val manifestFile = DockerUtil.getBlobGlobally(repo, manifestFilename, DockerSearchBlobPolicy.SHA_256)
            if (manifestFile != null) {
                val configBytes = DockerSchemaUtil.fetchSchema2Manifest(
                    repo,
                    DockerUtil.getFullPath(manifestFile)
                )
                addSchema2Blob(configBytes, manifestMetadata)
            }
        }
    }

    private fun manifestUploadContext(
        projectId: String,
        repoName: String,
        manifestType: ManifestType,
        manifestMetadata: ManifestMetadata,
        manifestPath: String,
        manifestBytes: ByteArray,
        artifactFile: ArtifactFile
    ): UploadContext {
        val context = UploadContext(projectId, repoName, manifestPath).content(ByteArrayInputStream(manifestBytes))
            .artifactFile(artifactFile)
        if ((manifestType == ManifestType.Schema2 || manifestType == ManifestType.Schema2List) && "sha256" == manifestMetadata.tagInfo.digest?.getDigestAlg()) {
            context.sha256(manifestMetadata.tagInfo.digest!!.getDigestHex())
        }

        return context
    }

    override fun isBlobExists(pathContext: RequestContext, digest: DockerDigest): ResponseEntity<Any> {
        try {
            RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
            logger.info("check blob exist [$pathContext] ,[${digest.getDigestHex()}]")
            if (DockerSchemaUtil.isEmptyBlob(digest)) {
                logger.info("request for empty layer for image [$pathContext, $digest]")
                return DockerSchemaUtil.emptyBlobHeadResponse()
            } else {
                val blob = DockerUtil.getBlobFromRepo(repo, pathContext, digest.filename()) ?: run {
                        return DockerV2Errors.blobUnknown(digest.toString())
                    }
                return ResponseEntity.ok().header("Docker-Distribution-Api-Version", "registry/2.0")
                        .header("Docker-Content-Digest", digest.toString())
                        .header("Content-Length", blob.length.toString())
                        .header("Content-Type", "application/octet-stream").build<Any>()
            }
        } catch (e: PermissionCheckException) {
            logger.warn("the user do not have permission to op")
            return DockerV2Errors.unauthorizedUpload()
        }
    }

    override fun getBlob(pathContext: RequestContext, digest: DockerDigest): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        if (DockerSchemaUtil.isEmptyBlob(digest)) {
            logger.warn("request for empty layer for image [${pathContext.dockerRepo}]")
            return DockerSchemaUtil.emptyBlobGetResponse()
        } else {
            val blob = getRepoBlob(pathContext.projectId, pathContext.repoName, pathContext.dockerRepo, digest) ?: run {
                    return DockerV2Errors.blobUnknown(digest.toString())
                }
            logger.info("fet blob [${digest.getDigestHex()}] from repo [${pathContext.dockerRepo}] ,length [${blob.length}]")
            val context =
                    DownloadContext(pathContext.projectId, pathContext.repoName, pathContext.dockerRepo)
                        .sha256(digest.getDigestHex()).length(blob.length)
                val inputStream = repo.download(context)
                httpHeaders.set("Docker-Distribution-Api-Version", "registry/2.0")
                httpHeaders.set("Docker-Content-Digest", digest.toString())
                val resource = InputStreamResource(inputStream)
                return ResponseEntity.ok()
                    .headers(httpHeaders)
                    .contentLength(blob.length)
                    .contentType(MediaType.parseMediaType("application/octet-stream"))
                    .body(resource)
        }
    }

    private fun getRepoBlob(projectId: String, repoName: String, dockerRepo: String, digest: DockerDigest): Artifact? {
        val result = repo.findArtifacts(projectId, repoName, digest.filename())
        if (result.isEmpty()) {
            return null
        }
        val length = result[0]["size"] as Int
        return Artifact(projectId, repoName, dockerRepo).sha256(digest.filename()).length(length.toLong())
    }

    override fun startBlobUpload(pathContext: RequestContext, mount: String?): ResponseEntity<Any> {
        try {
            RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
            logger.info("start upload blob : [$pathContext]")
            if (!repo.canWrite(
                    RequestContext(
                        pathContext.projectId,
                        pathContext.repoName,
                        pathContext.dockerRepo
                    )
                )
            ) {
                return DockerV2Errors.unauthorizedUpload()
            }
            val location: URI
            if (mount != null) {
                var mountDigest = DockerDigest(mount)
                val mountableBlob =
                    DockerUtil.findBlobGlobally(repo, pathContext, mountDigest.filename())
                if (mountableBlob != null) {
                    location = getDockerURI("${pathContext.dockerRepo}/blobs/$mount")
                    logger.info("found accessible blob at [$mountableBlob] to mount  [$pathContext] [$mount]")
                    return ResponseEntity.status(201).header("Docker-Distribution-Api-Version", "registry/2.0")
                        .header("Docker-Content-Digest", mount).header("Content-Length", "0")
                        .header("Location", location.toString()).build()
                }
            }
            val uuid = repo.startAppend()
            location = getDockerURI(
                "${pathContext.projectId}/${pathContext.repoName}/${pathContext.dockerRepo}/blobs/uploads/$uuid"
            )
            return ResponseEntity.status(202).header("Docker-Distribution-Api-Version", "registry/2.0")
                .header("Docker-Upload-Uuid", uuid).header("Location", location.toString()).build()
        } catch (e: PermissionCheckException) {
            return DockerV2Errors.unauthorizedUpload()
        }
    }

    private fun getDockerURI(path: String): URI {
        val hostHeaders = httpHeaders["Host"]
        var host = ""
        var port: Int? = null
        if (hostHeaders != null && !hostHeaders.isEmpty()) {
            val parts = (hostHeaders[0] as String).split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            host = parts[0]
            if (parts.size > 1) {
                port = Integer.valueOf(parts[1])
            }
        } else {
            logger.error("docker location url is blank, make sure the host request header exists.")
        }

        val builder = UriBuilder.fromPath("v2/$path").host(host).scheme(RepoServiceUtil.getProtocol(httpHeaders))
        if (port != null) {
            builder.port(port)
        }

        return builder.build(*arrayOfNulls(0))
    }

    override fun uploadBlob(
        pathContext: RequestContext,
        digest: DockerDigest,
        uuid: String,
        artifactFile: ArtifactFile
    ): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        return if (RepoServiceUtil.putHasStream(httpHeaders)) uploadBlobFromPut(
            pathContext, digest, artifactFile
        ) else finishPatchUpload(pathContext, digest, uuid)
    }

    private fun uploadBlobFromPut(
        pathContext: RequestContext,
        digest: DockerDigest,
        artifactFile: ArtifactFile
    ): ResponseEntity<Any> {
        val blobPath = pathContext.dockerRepo + "/" + "_uploads" + "/" + digest.filename()
        if (!repo.canWrite(pathContext)) {
            return RepoServiceUtil.consumeStreamAndReturnError(artifactFile.getInputStream())
        } else {
            logger.info("deploy docker blob [$blobPath] into [$pathContext]")
            val context =
                UploadContext(
                    pathContext.projectId,
                    pathContext.repoName,
                    blobPath
                ).content(artifactFile.getInputStream()).sha256(digest.getDigestHex())
            val response = repo.upload(context)
            return if (response) {
                val location = getDockerURI("${pathContext.dockerRepo}/blobs/$digest")
                ResponseEntity.created(location).header("Docker-Distribution-Api-Version", "registry/2.0")
                    .header("Docker-Content-Digest", digest.toString()).build()
            } else {
                logger.error("error upload blob [$blobPath]")
                DockerV2Errors.blobUploadInvalid(response.toString())
            }
        }
    }

    private fun finishPatchUpload(
        pathContext: RequestContext,
        digest: DockerDigest,
        uuid: String
    ): ResponseEntity<Any> {
        logger.info("finish upload blob [$digest]")
        val fileName = digest.filename()
        val blobPath = "/${pathContext.dockerRepo}/_uploads/$fileName"
        var context = UploadContext(pathContext.projectId, pathContext.repoName, blobPath)
        repo.finishAppend(uuid, context)
        val location =
            getDockerURI("${pathContext.projectId}/${pathContext.repoName}/${pathContext.dockerRepo}/blobs/$digest")
        return ResponseEntity.created(location).header("Docker-Distribution-Api-Version", "registry/2.0")
            .header("Content-Length", "0").header("Docker-Content-Digest", digest.toString()).build()
    }

    override fun patchUpload(
        pathContext: RequestContext,
        uuid: String,
        artifactFile: ArtifactFile
    ): ResponseEntity<Any> {
        RepoUtil.loadRepo(repo, userId, pathContext.projectId, pathContext.repoName)
        logger.info("patch upload blob [$uuid]")
        val appendId = repo.writeAppend(uuid, artifactFile)
        val location = getDockerURI(
            "${pathContext.projectId}/${pathContext.repoName}/${pathContext.dockerRepo}/blobs/uploads/$uuid"
        )
        return ResponseEntity.status(202).header("Content-Length", "0")
            .header("Docker-Distribution-Api-Version", "registry/2.0").header("Docker-Upload-Uuid", uuid)
            .header("Location", location.toString()).header("Range", "0-" + (appendId - 1L)).build()
    }
}
