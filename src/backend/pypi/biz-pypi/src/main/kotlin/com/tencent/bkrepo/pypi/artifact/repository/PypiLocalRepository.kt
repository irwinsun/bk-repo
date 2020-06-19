package com.tencent.bkrepo.pypi.artifact.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.hash.md5
import com.tencent.bkrepo.common.artifact.hash.sha256
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactListContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactMigrateContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactSearchContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.local.LocalRepository
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.resolve.file.multipart.MultipartArtifactFile
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.pypi.artifact.PypiArtifactInfo
import com.tencent.bkrepo.pypi.artifact.model.MigrateDataCreateNode
import com.tencent.bkrepo.pypi.artifact.model.MigrateDataInfo
import com.tencent.bkrepo.pypi.artifact.model.TMigrateData
import com.tencent.bkrepo.pypi.artifact.xml.Value
import com.tencent.bkrepo.pypi.artifact.xml.XmlUtil
import com.tencent.bkrepo.pypi.exception.PypiMigrateReject
import com.tencent.bkrepo.pypi.pojo.PypiMigrateResponse
import com.tencent.bkrepo.pypi.util.DecompressUtil.getPkgInfo
import com.tencent.bkrepo.pypi.util.FileNameUtil.fileFormat
import com.tencent.bkrepo.pypi.util.HttpUtil.downloadUrlHttpClient
import com.tencent.bkrepo.pypi.util.JsoupUtil.htmlHrefs
import com.tencent.bkrepo.pypi.util.JsoupUtil.sumTasks
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 *
 * @author: carrypan
 * @date: 2019/12/4
 */
@Component
@Primary
class PypiLocalRepository : LocalRepository(), PypiRepository {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var migrateDataRepository: MigrateDataRepository

    override fun onUpload(context: ArtifactUploadContext) {
        val nodeCreateRequest = getNodeCreateRequest(context)
        nodeResource.create(nodeCreateRequest)
        context.getArtifactFile("content")?.let {
            storageService.store(nodeCreateRequest.sha256!!,
                it, context.storageCredentials)
        }
    }

    /**
     * 获取PYPI节点创建请求
     */
    override fun getNodeCreateRequest(context: ArtifactUploadContext): NodeCreateRequest {
        val artifactInfo = context.artifactInfo
        val repositoryInfo = context.repositoryInfo
        val artifactFile = context.getArtifactFile()
        val filename = (artifactFile as MultipartArtifactFile).getOriginalFilename()
        val sha256 = artifactFile.getInputStream().sha256()
        val md5 = artifactFile.getInputStream().md5()
        val pypiArtifactInfo = artifactInfo as PypiArtifactInfo

        return NodeCreateRequest(
            projectId = repositoryInfo.projectId,
            repoName = repositoryInfo.name,
            folder = false,
            overwrite = true,
            fullPath = artifactInfo.artifactUri + "/$filename",
            size = artifactFile.getSize(),
            sha256 = sha256 as String?,
            md5 = md5 as String?,
            operator = context.userId,
            metadata = pypiArtifactInfo.metadata
        )
    }

    override fun searchNodeList(context: ArtifactSearchContext, xmlString: String): MutableList<Value>? {
        val repository = context.repositoryInfo
        val searchArgs = XmlUtil.getSearchArgs(xmlString)
        val packageName = searchArgs["packageName"]
        val summary = searchArgs["summary"]
        if (packageName != null && summary != null) {
            with(repository) {
                val projectId = Rule.QueryRule("projectId", projectId)
                val repoName = Rule.QueryRule("repoName", name)
                val packageQuery = Rule.QueryRule("metadata.name", packageName, OperationType.MATCH)
                val filetypeAuery = Rule.QueryRule("metadata.filetype", "bdist_wheel")
                val summaryQuery = Rule.QueryRule("metadata.summary", summary, OperationType.MATCH)
                // val versionQuery = Rule.QueryRule("metadata.verison", OperationType.EQ)
                val rule1 = Rule.NestedRule(
                    mutableListOf(repoName, projectId, packageQuery, filetypeAuery),
                    Rule.NestedRule.RelationType.AND
                )
                val rule2 = Rule.NestedRule(mutableListOf(rule1, summaryQuery), Rule.NestedRule.RelationType.OR)

                val queryModel = QueryModel(
                    page = PageLimit(0, 10),
                    sort = Sort(listOf("name"), Sort.Direction.ASC),
                    select = mutableListOf("projectId", "repoName", "fullPath", "metadata"),
                    rule = rule1
                )
                val nodeList: List<Map<String, Any>>? = nodeResource.query(queryModel).data?.records
                if (nodeList != null) {
                    return XmlUtil.nodeLis2Values(nodeList)
                }
            }
        }
        return null
    }

    /**
     *
     */
    override fun list(context: ArtifactListContext) {
        val artifactInfo = context.artifactInfo
        val repositoryInfo = context.repositoryInfo
        with(artifactInfo) {
            val nodeDetail = nodeResource.detail(projectId, repositoryInfo.name, artifactUri).data
                    ?: throw com.tencent.bkrepo.common.api.exception.ErrorCodeException(
                            com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode.NODE_NOT_FOUND,
                            artifactUri
                    )

            val response = HttpContextHolder.getResponse()
            response.contentType = "text/html; charset=UTF-8"
            // 请求不带包名，返回包名列表.
            if (artifactUri == "/") {
                if (nodeDetail.nodeInfo.folder) {
                    val nodeList = nodeResource.list(projectId, repositoryInfo.name, artifactUri, includeFolder = true, deep = true).data
                            ?: throw com.tencent.bkrepo.common.api.exception.ErrorCodeException(
                                    com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode.NODE_NOT_FOUND,
                                    artifactInfo.artifactUri
                            )
                    // 过滤掉'根节点',
                    val htmlContent = buildPackageListContent(
                        artifactInfo.projectId,
                        artifactInfo.repoName,
                        nodeList.filter { it.folder }.filter { it.path == "/" })
                    response.writer.print(htmlContent)
                }
            }
            // 请求中带包名，返回对应包的文件列表。
            else {
                if (nodeDetail.nodeInfo.folder) {
                    val packageNode = nodeResource.list(projectId, repositoryInfo.name, artifactUri, includeFolder = false, deep = true).data
                            ?: throw com.tencent.bkrepo.common.api.exception.ErrorCodeException(
                                    com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode.NODE_NOT_FOUND,
                                    artifactUri
                            )
                    val htmlContent = buildPypiPageContent(buildPackageFilenodeListContent(artifactInfo.projectId, artifactInfo.repoName, packageNode))
                    response.writer.print(htmlContent)
                }
            }
        }
    }

    /**
     * html 页面公用的元素
     * @param listContent 显示的内容
     */
    private fun buildPypiPageContent(listContent: String): String {
        return """
            <html>
                <head><title>Simple Index</title><meta name="api-version" value="2" /></head>
                <body>
                    $listContent
                </body>
            </html>
        """.trimIndent()
    }

    /**
     * 对应包中的文件列表
     * @param projectId
     * @param repoName
     * @param nodeList
     */
    private fun buildPackageFilenodeListContent(projectId: String, repoName: String, nodeList: List<NodeInfo>): String {
        val builder = StringBuilder()
        if (nodeList.isEmpty()) {
            builder.append("The directory is empty.")
        }
        for (node in nodeList) {
            val md5 = node.md5
            // 查询的对应的文件节点的metadata
            val metadata = filenodeMetadata(node)
            builder.append("<a data-requires-python=\">=$metadata[\"requires_python\"]\" href=\"/$projectId/$repoName/packages${node.fullPath}#md5=$md5\" rel=\"internal\" >${node.name}</a><br/>")
        }
        return builder.toString()
    }

    /**
     * 所有包列表
     * @param projectId
     * @param repoName
     * @param nodeList
     */
    private fun buildPackageListContent(projectId: String, repoName: String, nodeList: List<NodeInfo>): String {
        val builder = StringBuilder()
        if (nodeList.isEmpty()) {
            builder.append("The directory is empty.")
        }
        for (node in nodeList) {
            builder.append("<a data-requires-python=\">=\" href=\"/$projectId/$repoName/simple/${node.name}\" rel=\"internal\" >${node.name}</a><br/>")
        }
        return builder.toString()
    }

    /**
     * 根据每个文件节点数据去查metadata
     * @param nodeInfo 节点
     */
    fun filenodeMetadata(nodeInfo: NodeInfo): List<Map<String, Any>>? {
        val filenodeList: List<Map<String, Any>>?
        with(nodeInfo) {
            val projectId = Rule.QueryRule("projectId", projectId)
            val repoName = Rule.QueryRule("repoName", repoName)
            val packageQuery = Rule.QueryRule("metadata.name", name, OperationType.EQ)
            val fullPathQuery = Rule.QueryRule("fullPath", fullPath)
            val rule1 = Rule.NestedRule(
                mutableListOf(repoName, projectId, packageQuery, fullPathQuery),
                Rule.NestedRule.RelationType.AND
            )
            val queryModel = QueryModel(
                page = PageLimit(0, 10),
                sort = Sort(listOf("name"), Sort.Direction.ASC),
                select = mutableListOf("projectId", "repoName", "fullPath", "metadata"),
                rule = rule1
            )
            filenodeList = nodeResource.query(queryModel).data?.records
        }
        return filenodeList
    }

    @org.springframework.beans.factory.annotation.Value("\${migrate.url}")
    private lateinit var migrateUrl: String

    @org.springframework.beans.factory.annotation.Value("\${limitPackages}")
    private lateinit var limitPackages: String

    private val failSet = mutableSetOf<String>()

    fun migrateResult(context: ArtifactMigrateContext): PypiMigrateResponse<String> {
        with(context.artifactInfo) {
            val migrateDataInfo = findMigrateResult(projectId, repoName)
            migrateDataInfo?.let {
                 return PypiMigrateResponse(migrateDataInfo.description,
                        migrateDataInfo.filesNum,
                migrateDataInfo.filesNum - migrateDataInfo.errorData.size,
                        migrateDataInfo.errorData.size,
                        migrateDataInfo.elapseTimeSeconds,
                        migrateDataInfo.errorData as Set<String>,
                        migrateDataInfo.createdDate)
            }
            return PypiMigrateResponse("未找到数据迁移记录，如果已经调用迁移接口{migrate/url},请稍后查询")
        }
    }

    fun findMigrateResult(projectId: String, repoName: String): MigrateDataInfo? {
        val criteria =
                Criteria.where(TMigrateData::projectId.name).`is`(projectId).and(TMigrateData::repoName.name)
                        .`is`(repoName)
        val query = Query.query(criteria).with(org.springframework.data.domain.Sort(org.springframework.data.domain.Sort.Direction.DESC, TMigrateData::lastModifiedDate.name)).limit(0)
        return mongoTemplate.findOne(query, TMigrateData::class.java)?.let { convert(it) }
    }

    fun migrateData(context: ArtifactMigrateContext): PypiMigrateResponse<String> {
        val job = GlobalScope.launch {
            migrate(context)
        }
        job.start()
        return migrateResult(context)
    }

    override fun migrate(context: ArtifactMigrateContext) {
        val verifiedUrl = beforeMigrate()

        var totalCount: Int
        val cpuCore = cpuCore()
        val threadPool = ThreadPoolExecutor(cpuCore, cpuCore * 2, 15, TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                ThreadFactoryBuilder().setNameFormat("pypiRepo-migrate-thread-%d").build(),
                PypiMigrateReject())

        // 获取所有的包,开始计时
        val start = System.currentTimeMillis()
        verifiedUrl.htmlHrefs(limitPackages.toInt()).let { simpleHrefs ->
            totalCount = migrateUrl.sumTasks(simpleHrefs)
            for (e in simpleHrefs) {
                // 每一个包所包含的文件列表
                e.text()?.let { packageName ->
                    "$verifiedUrl/$packageName".htmlHrefs().let { filenodes ->
                        for (filenode in filenodes) {
                            threadPool.submit(Runnable {
                                migrateUpload(context, filenode, verifiedUrl, packageName)
                            })
                        }
                    }
                }
            }
        }

        threadPool.shutdown()
        while (!threadPool.awaitTermination(2, TimeUnit.SECONDS)) {}
        val end = System.currentTimeMillis()
        val elapseTimeSeconds = (end - start) / 1000
        insertMigrateData(context.artifactInfo.projectId,
                context.artifactInfo.repoName,
                failSet,
                limitPackages.toInt(),
                totalCount,
                elapseTimeSeconds)
    }

    private fun insertMigrateData(
        projectId: String,
        repoName: String,
        collect: Set<String>,
        packagesName: Int,
        filesNum: Int,
        elapseTimeSeconds: Long
    ) {
        val dataCreateRequest = MigrateDataCreateNode(
                projectId = projectId,
                repoName = repoName,
                errorData = jacksonObjectMapper().writeValueAsString(collect),
                packagesNum = packagesName,
                filesNum = filesNum,
                elapseTimeSeconds = elapseTimeSeconds,
                description = "最近一次任务 {$migrateUrl} 迁移结果如下，请注意检查迁移完成时间"
        )
        create(dataCreateRequest)
    }

    @Transactional(rollbackFor = [Throwable::class])
    fun create(dataCreateRequest: MigrateDataCreateNode) {
        with(dataCreateRequest) {
            val errorData = TMigrateData(
                    projectId = projectId,
                    repoName = repoName,
                    errorData = errorData,
                    createdBy = createdBy,
                    createdDate = java.time.LocalDateTime.now(),
                    lastModifiedBy = createdBy,
                    lastModifiedDate = java.time.LocalDateTime.now(),
                    packagesNum = packagesNum,
                    filesNum = filesNum,
                    elapseTimeSeconds = elapseTimeSeconds,
                    description = description
            )
            migrateDataRepository.insert(errorData)
                    .also { logger.info("Create migration error data [$dataCreateRequest] success.") }
        }
    }

    fun migrateUpload(context: ArtifactMigrateContext, filenode: Element, verifiedUrl: String, packageName: String) {
        try {
            val filename = filenode.text()
            val hrefValue = filenode.attributes()["href"]
            // 获取文件流
            val byteStream = "$verifiedUrl/$packageName/$hrefValue".downloadUrlHttpClient()
            byteStream?.use {
                val artifactFile = ArtifactFileFactory.build(byteStream)
                val nodeCreateRequest = createMigrateNode(context, artifactFile, packageName, filename)
                nodeCreateRequest?.let {
                    nodeResource.create(nodeCreateRequest)
                    artifactFile.let {
                        storageService.store(nodeCreateRequest.sha256!!,
                                it, context.storageCredentials)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e.message)
            logger.warn("$verifiedUrl/$packageName/${filenode.attributes()["href"]}")
            failSet.add("$verifiedUrl/$packageName/${filenode.attributes()["href"]}")
        }
    }

    fun createMigrateNode(context: ArtifactMigrateContext, artifactFile: ArtifactFile, packageName: String, filename: String): NodeCreateRequest? {
        val artifactInfo = context.artifactInfo
        val repositoryInfo = context.repositoryInfo
        // 获取文件版本信息
        val pkgInfo = filename.fileFormat()?.let { artifactFile.getInputStream().getPkgInfo(it) }
        // 文件fullPath
        val path = "/$packageName/${pkgInfo?.get("version")}/$filename"

        nodeResource.exist(repositoryInfo.projectId, repositoryInfo.name, path).data?.let {
            if (it) {
                return null
            }
        }
        val sha256 = artifactFile.getInputStream().sha256()
        val md5 = artifactFile.getInputStream().md5()
        val pypiArtifactInfo = artifactInfo as PypiArtifactInfo

        return NodeCreateRequest(
                projectId = repositoryInfo.projectId,
                repoName = repositoryInfo.name,
                folder = false,
                overwrite = true,
                fullPath = path,
                size = artifactFile.getSize(),
                sha256 = sha256,
                md5 = md5,
                operator = context.userId,
                metadata = pypiArtifactInfo.metadata
        )
    }

    /**
     * 检验地址格式
     */
    fun beforeMigrate(): String {
//        val okHttp = HttpClientBuilderFactory.create().build()
//        val request = Request.Builder().get().url(migrateUrl).build()
//        val response = okHttp.newCall(request).execute()
//        if (!(response.message().equals("OK", true))) {
//            return PypiMigrateResponse("$migrateUrl cannot reach")
//        }
        return migrateUrl.removeSuffix("/")
    }

    /**
     * 获取CPU核心数
     */
    fun cpuCore(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(PypiLocalRepository::class.java)
        fun convert(tMigrateData: TMigrateData): MigrateDataInfo {
            return tMigrateData.let {
                MigrateDataInfo(
                        errorData = jacksonObjectMapper().readValue(it.errorData, Set::class.java),
                        projectId = it.projectId,
                        repoName = it.repoName,
                        createdBy = it.createdBy,
                        createdDate = it.createdDate.format(DateTimeFormatter.ISO_DATE_TIME),
                        packagesNum = it.packagesNum,
                        filesNum = it.filesNum,
                        elapseTimeSeconds = it.elapseTimeSeconds,
                        description = it.description
                )
            }
        }
    }
}
