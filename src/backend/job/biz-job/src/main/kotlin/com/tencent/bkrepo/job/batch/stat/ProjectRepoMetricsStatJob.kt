/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2022 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.job.batch.stat

import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bkrepo.job.CREATED_DATE
import com.tencent.bkrepo.job.DELETED_DATE
import com.tencent.bkrepo.job.PATH
import com.tencent.bkrepo.job.PROJECT
import com.tencent.bkrepo.job.REPO
import com.tencent.bkrepo.job.SHARDING_COUNT
import com.tencent.bkrepo.job.batch.base.ActiveProjectService
import com.tencent.bkrepo.job.batch.base.DefaultContextMongoDbJob
import com.tencent.bkrepo.job.batch.base.JobContext
import com.tencent.bkrepo.job.batch.context.ProjectRepoMetricsStatJobContext
import com.tencent.bkrepo.job.batch.utils.FolderUtils
import com.tencent.bkrepo.job.batch.utils.MongoShardingUtils
import com.tencent.bkrepo.job.config.properties.MongodbJobProperties
import com.tencent.bkrepo.job.pojo.project.TProjectMetrics
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass

/**
 * 项目仓库指标统计任务
 */
open class ProjectRepoMetricsStatJob(
    properties: MongodbJobProperties,
    private val activeProjectService: ActiveProjectService,
    private val active: Boolean = true,
) : DefaultContextMongoDbJob<ProjectRepoMetricsStatJob.Repository>(properties) {

    override fun collectionNames(): List<String> {
        return listOf(COLLECTION_REPOSITORY_NAME)
    }

    override fun buildQuery(): Query = Query(Criteria.where(DELETED_DATE).`is`(null))

    override fun mapToEntity(row: Map<String, Any?>): Repository {
        return Repository(row)
    }

    override fun entityClass(): KClass<Repository> {
        return Repository::class
    }

    open fun statProjectCheck(
        projectId: String,
        context: ProjectRepoMetricsStatJobContext
    ): Boolean = true

    override fun run(row: Repository, collectionName: String, context: JobContext) {
        require(context is ProjectRepoMetricsStatJobContext)
        with(row) {
            if (!statProjectCheck(projectId, context)) return
            val query = Query(
                Criteria.where(PROJECT).isEqualTo(projectId).and(REPO).isEqualTo(name)
                    .and(PATH).isEqualTo(PathUtils.ROOT).and(DELETED_DATE).isEqualTo(null)
            )
            val nodeCollectionName = COLLECTION_NODE_PREFIX +
                MongoShardingUtils.shardingSequence(projectId, SHARDING_COUNT)
            val nodes = mongoTemplate.find(query, Node::class.java, nodeCollectionName)
            if (nodes.isEmpty()) return
            nodes.forEach {
                val key = FolderUtils.buildCacheKey(collectionName = nodeCollectionName, projectId = projectId)
                val metric = context.metrics.getOrPut(key) {
                    ProjectRepoMetricsStatJobContext.ProjectMetrics(projectId)
                }
                if (!it.folder) {
                    metric.nodeNum.increment()
                } else {
                    val nodeNum = it.nodeNum ?: 0
                    metric.nodeNum.add(nodeNum)
                }
                metric.capSize.add(it.size)
                metric.addRepoMetrics(row = it, credentialsKey = credentialsKey, repoType = type)
            }
        }
    }

    override fun getLockAtMostFor(): Duration {
        return Duration.ofDays(7)
    }

    override fun onRunCollectionFinished(collectionName: String, context: JobContext) {
        super.onRunCollectionFinished(collectionName, context)
        require(context is ProjectRepoMetricsStatJobContext)
        logger.info("start to insert [active: ${this.active}] project's metrics")
        for (entry in context.metrics) {
            storeMetrics(context.statDate, entry.value.toDO(active, context.statDate))
        }
        context.metrics.clear()
        logger.info("stat [active: ${this.active}] project metrics done")
    }

    override fun createJobContext(): ProjectRepoMetricsStatJobContext{
        return ProjectRepoMetricsStatJobContext(
            statDate = LocalDate.now().atStartOfDay(),
            statProjects = activeProjectService.getActiveProjects()
        )
    }


    private fun storeMetrics(
        statDate: LocalDateTime,
        projectMetric: TProjectMetrics
    ) {
        // insert project repo metrics
        val criteria = Criteria.where(CREATED_DATE).isEqualTo(statDate).and(PROJECT).`is`(projectMetric.projectId)
        mongoTemplate.remove(Query(criteria), COLLECTION_NAME_PROJECT_METRICS)
        logger.info("stat project: [${projectMetric.projectId}], size: [${projectMetric.capSize}]")
        mongoTemplate.insert(projectMetric, COLLECTION_NAME_PROJECT_METRICS)
    }

    data class Repository(
        var projectId: String,
        var name: String,
        var type: String,
        var credentialsKey: String = "default",
    ) {
        constructor(map: Map<String, Any?>) : this(
            map[Repository::projectId.name].toString(),
            map[Repository::name.name].toString(),
            map[Repository::type.name].toString(),
            map[Repository::credentialsKey.name]?.toString() ?: "default",
        )
    }

    data class Node(
        val projectId: String,
        val repoName: String,
        val folder: Boolean,
        val fullPath: String,
        val size: Long,
        val nodeNum: Long? = null
    ) {
        constructor(map: Map<String, Any?>) : this(
            map[Node::projectId.name].toString(),
            map[Node::repoName.name].toString(),
            map[Node::folder.name] as Boolean,
            map[Node::fullPath.name].toString(),
            map[Node::size.name].toString().toLong(),
            map[Node::nodeNum.name]?.toString()?.toLong()
        )
    }


    companion object {
        private val logger = LoggerFactory.getLogger(ProjectRepoMetricsStatJob::class.java)
        private const val COLLECTION_REPOSITORY_NAME = "repository"
        private const val COLLECTION_NODE_PREFIX = "node_"
        private const val COLLECTION_NAME_PROJECT_METRICS = "project_metrics"
    }
}
