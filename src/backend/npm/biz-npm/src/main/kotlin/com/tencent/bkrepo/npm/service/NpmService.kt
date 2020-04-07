package com.tencent.bkrepo.npm.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.artifact.api.ArtifactFileMap
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.permission.Permission
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactRemoveContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactSearchContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.context.RepositoryHolder
import com.tencent.bkrepo.npm.artifact.NpmArtifactInfo
import com.tencent.bkrepo.npm.constants.APPLICATION_OCTET_STEAM
import com.tencent.bkrepo.npm.constants.ATTACHMENTS
import com.tencent.bkrepo.npm.constants.ATTRIBUTE_OCTET_STREAM_SHA1
import com.tencent.bkrepo.npm.constants.CONTENT_TYPE
import com.tencent.bkrepo.npm.constants.CREATED
import com.tencent.bkrepo.npm.constants.DATA
import com.tencent.bkrepo.npm.constants.DIST
import com.tencent.bkrepo.npm.constants.DISTTAGS
import com.tencent.bkrepo.npm.constants.FILE_DASH
import com.tencent.bkrepo.npm.constants.FILE_SUFFIX
import com.tencent.bkrepo.npm.constants.KEYWORDS
import com.tencent.bkrepo.npm.constants.LATEST
import com.tencent.bkrepo.npm.constants.MODIFIED
import com.tencent.bkrepo.npm.constants.NAME
import com.tencent.bkrepo.npm.constants.NPM_FILE_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_METADATA
import com.tencent.bkrepo.npm.constants.NPM_PACKAGE_JSON_FILE
import com.tencent.bkrepo.npm.constants.NPM_PACKAGE_TGZ_FILE
import com.tencent.bkrepo.npm.constants.NPM_PACKAGE_VERSION_JSON_FILE
import com.tencent.bkrepo.npm.constants.NPM_PKG_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_JSON_FILE_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_TGZ_FILE_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_TGZ_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_VERSION_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_VERSION_JSON_FILE_FULL_PATH
import com.tencent.bkrepo.npm.constants.REV
import com.tencent.bkrepo.npm.constants.SHASUM
import com.tencent.bkrepo.npm.constants.TIME
import com.tencent.bkrepo.npm.constants.VERSION
import com.tencent.bkrepo.npm.constants.VERSIONS
import com.tencent.bkrepo.npm.constants.revValue
import com.tencent.bkrepo.npm.pojo.NpmMetaData
import com.tencent.bkrepo.npm.utils.BeanUtils
import com.tencent.bkrepo.npm.utils.GsonUtils
import com.tencent.bkrepo.repository.api.MetadataResource
import com.tencent.bkrepo.repository.pojo.metadata.MetadataSaveRequest
import org.apache.commons.codec.binary.Base64
import org.apache.commons.fileupload.util.Streams
import org.apache.commons.lang.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStreamReader
import java.util.Date

@Service
class NpmService @Autowired constructor(
    private val metadataResource: MetadataResource
) {

    @Permission(ResourceType.REPO, PermissionAction.WRITE)
    @Transactional(rollbackFor = [Throwable::class])
    fun publish(userId: String, artifactInfo: NpmArtifactInfo, body: String) {
        body.takeIf { StringUtils.isNotBlank(it) } ?: throw ArtifactNotFoundException("request body not found!")
        val jsonObj = JsonParser().parse(body).asJsonObject
        val artifactFileMap = ArtifactFileMap()
        if (jsonObj.has(ATTACHMENTS)) {
            val attributesMap = mutableMapOf<String, Any>()
            buildTgzFile(artifactFileMap, jsonObj, attributesMap)
            buildPkgVersionFile(artifactFileMap, jsonObj, attributesMap)
            buildPkgFile(artifactInfo, artifactFileMap, jsonObj)
            val context = ArtifactUploadContext(artifactFileMap)
            context.contextAttributes = attributesMap
            val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
            repository.upload(context)
        } else {
            unPublishOperation(artifactInfo, jsonObj)
        }
    }

    private fun unPublishOperation(artifactInfo: NpmArtifactInfo, jsonObj: JsonObject) {
        // 非publish操作 deprecate等操作
        val versions = jsonObj.getAsJsonObject(VERSIONS)
        versions.keySet().forEach {
            val name = jsonObj.get(NAME).asString
            val version = versions.getAsJsonObject(it)[VERSION].asString
            val metaData = buildMetaData(versions[it].asJsonObject)
            val tgzFullPath = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version)
            metadataResource.save(
                MetadataSaveRequest(
                    artifactInfo.projectId,
                    artifactInfo.repoName,
                    tgzFullPath,
                    metaData
                )
            )
        }
    }

    /**
     * 构造package.json文件
     */
    private fun buildPkgFile(artifactInfo: NpmArtifactInfo, artifactFileMap: ArtifactFileMap, jsonObj: JsonObject) {
        // 读取package.json文件去追加内容
        var pkgInfo = searchPackageInfo(artifactInfo) ?: JsonObject()
        val leastJsonObject = jsonObj.getAsJsonObject(VERSIONS)

        // 如果是第一次上传
        val timeMap = if (pkgInfo.size() == 0) pkgInfo else pkgInfo.getAsJsonObject(TIME)!!
        if (pkgInfo.size() == 0) {
            jsonObj.addProperty(REV, revValue)
            pkgInfo = jsonObj
            timeMap.add(CREATED, GsonUtils.gson.toJsonTree(Date()))
        }

        val version = jsonObj.getAsJsonObject(DISTTAGS).get(LATEST).asString
        pkgInfo.getAsJsonObject(VERSIONS).add(version, leastJsonObject.getAsJsonObject(version))
        pkgInfo.getAsJsonObject(DISTTAGS).addProperty(LATEST, version)
        timeMap.add(version, GsonUtils.gson.toJsonTree(Date()))
        timeMap.add(MODIFIED, GsonUtils.gson.toJsonTree(Date()))
        pkgInfo.add(TIME, timeMap)
        val packageJsonFile = ArtifactFileFactory.build()
        Streams.copy(GsonUtils.gson.toJson(pkgInfo).byteInputStream(), packageJsonFile.getOutputStream(), true)
        artifactFileMap[NPM_PACKAGE_JSON_FILE] = packageJsonFile
    }

    /**
     * 构造pkgName-version.json文件
     */
    private fun buildPkgVersionFile(
        artifactFileMap: ArtifactFileMap,
        jsonObj: JsonObject,
        attributesMap: MutableMap<String, Any>
    ) {
        val version = jsonObj.getAsJsonObject(DISTTAGS).get(LATEST).asString
        val name = jsonObj.get(NAME).asString
        val versionJsonObj = jsonObj.getAsJsonObject(VERSIONS).getAsJsonObject(version)
        val packageJsonWithVersionFile = ArtifactFileFactory.build()
        Streams.copy(
            GsonUtils.gson.toJson(versionJsonObj).byteInputStream(),
            packageJsonWithVersionFile.getOutputStream(),
            true
        )
        artifactFileMap[NPM_PACKAGE_VERSION_JSON_FILE] = packageJsonWithVersionFile
        // 添加相关属性
        attributesMap[ATTRIBUTE_OCTET_STREAM_SHA1] = versionJsonObj.getAsJsonObject(DIST).get(SHASUM).asString
        attributesMap[NPM_METADATA] = buildMetaData(versionJsonObj)
        attributesMap[NPM_PKG_VERSION_JSON_FILE_FULL_PATH] =
            String.format(NPM_PKG_VERSION_FULL_PATH, name, name, version)
        attributesMap[NPM_PKG_JSON_FILE_FULL_PATH] = String.format(NPM_PKG_FULL_PATH, name)
    }

    private fun buildMetaData(versionJsonObj: JsonObject): Map<String, String> {
        val metaData = GsonUtils.gson.fromJson(versionJsonObj, NpmMetaData::class.java)
        return BeanUtils.beanToMap(metaData)
    }

    /**
     * 构造pkgName-version.tgz文件
     */
    private fun buildTgzFile(
        artifactFileMap: ArtifactFileMap,
        jsonObj: JsonObject,
        attributesMap: MutableMap<String, Any>
    ) {
        val attachments = getAttachmentsInfo(jsonObj, attributesMap)
        val tgzFile = ArtifactFileFactory.build()
        Streams.copy(
            Base64.decodeBase64(attachments.get(DATA)?.asString).inputStream(),
            tgzFile.getOutputStream(),
            true
        )
        artifactFileMap[NPM_PACKAGE_TGZ_FILE] = tgzFile
    }

    /**
     * 获取文件模块相关信息，最后将文件信息移除（data量容易过大）
     */
    private fun getAttachmentsInfo(jsonObj: JsonObject, attributesMap: MutableMap<String, Any>): JsonObject {
        val name = jsonObj.get(NAME).asString
        val version = jsonObj.getAsJsonObject(DISTTAGS).get(LATEST).asString
        logger.info("current pkgName : $name ,current version : $version")
        val attachKey = "$name$FILE_DASH$version$FILE_SUFFIX"
        val mutableMap = jsonObj.getAsJsonObject(ATTACHMENTS).getAsJsonObject(attachKey)
        attributesMap[NPM_PKG_TGZ_FILE_FULL_PATH] = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version)
        attributesMap[APPLICATION_OCTET_STEAM] = mutableMap.get(CONTENT_TYPE).asString
        jsonObj.remove(ATTACHMENTS)
        return mutableMap
    }

    @Permission(ResourceType.REPO, PermissionAction.READ)
    @Transactional(rollbackFor = [Throwable::class])
    fun searchPackageInfo(artifactInfo: NpmArtifactInfo): JsonObject? {
        val context = ArtifactSearchContext()
        context.contextAttributes[NPM_FILE_FULL_PATH] = getFileFullPath(artifactInfo)
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        val file = repository.search(context) ?: return null
        val fileJson = GsonUtils.gson.fromJson<JsonObject>(
            InputStreamReader(file.inputStream()),
            object : TypeToken<JsonObject>() {}.type
        )
        return getPkgInfo(fileJson, artifactInfo)
    }

    private fun getPkgInfo(fileJson: JsonObject, artifactInfo: NpmArtifactInfo): JsonObject {
        val versions = fileJson.getAsJsonObject(VERSIONS)
        if (StringUtils.isNotBlank(artifactInfo.version)) {
            val name = fileJson.get(NAME).asString
            val version = fileJson[VERSION].asString
            val tgzFullPath = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version)
            val metadataInfo =
                metadataResource.query(artifactInfo.projectId, artifactInfo.repoName, tgzFullPath).data
            metadataInfo?.forEach { (key, value) ->
                if (StringUtils.isNotBlank(value)) fileJson.addProperty(key, value)
                if (key == KEYWORDS) fileJson.add(key, GsonUtils.stringToArray(value))
            }
        } else {
            versions.keySet().forEach {
                val name = fileJson.get(NAME).asString
                val version = versions.getAsJsonObject(it)[VERSION].asString
                val tgzFullPath = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version)
                val metadataInfo =
                    metadataResource.query(artifactInfo.projectId, artifactInfo.repoName, tgzFullPath).data
                metadataInfo?.forEach { (key, value) ->
                    if (StringUtils.isNotBlank(value)) versions.getAsJsonObject(it).addProperty(key, value)
                    if (key == KEYWORDS) versions.getAsJsonObject(it).add(key, GsonUtils.stringToArray(value))
                }
            }
        }
        return fileJson
    }

    private fun getFileFullPath(artifactInfo: NpmArtifactInfo): String {
        val scope = artifactInfo.scope
        val pkgName = artifactInfo.pkgName
        val version = artifactInfo.version
        val scopePkg = if (StringUtils.isEmpty(scope)) pkgName else "$scope/$pkgName"
        return if (StringUtils.isEmpty(version)) String.format(NPM_PKG_FULL_PATH, scopePkg) else String.format(
            NPM_PKG_VERSION_FULL_PATH,
            scopePkg,
            scopePkg,
            version
        )
    }

    @Permission(ResourceType.REPO, PermissionAction.READ)
    @Transactional(rollbackFor = [Throwable::class])
    fun download(artifactInfo: NpmArtifactInfo) {
        val context = ArtifactDownloadContext()
        context.contextAttributes[NPM_FILE_FULL_PATH] =
            "/${artifactInfo.scope}/${artifactInfo.pkgName}/-/${artifactInfo.scope}/${artifactInfo.artifactUri}"
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        repository.download(context)
    }

    @Permission(ResourceType.REPO, PermissionAction.WRITE)
    fun unpublish(userId: String, artifactInfo: NpmArtifactInfo) {
        val fullPathList = mutableListOf<String>()
        val pkgInfo = searchPackageInfo(artifactInfo)!!
        val name = pkgInfo[NAME].asString
        pkgInfo.getAsJsonObject(VERSIONS).keySet().forEach { version ->
            fullPathList.add(String.format(NPM_PKG_VERSION_FULL_PATH, name, name, version))
            fullPathList.add(String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version))
        }
        fullPathList.add(String.format(NPM_PKG_FULL_PATH, name))
        val context = ArtifactRemoveContext()
        context.contextAttributes[NPM_FILE_FULL_PATH] = fullPathList
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        repository.remove(context)
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(NpmService::class.java)
    }
}