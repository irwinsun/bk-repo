package com.tencent.bkrepo.job.batch.utils

import com.tencent.bkrepo.common.mongo.constant.ID
import com.tencent.bkrepo.common.mongo.constant.MIN_OBJECT_ID
import com.tencent.bkrepo.common.mongo.dao.util.sharding.HashShardingUtils
import com.tencent.bkrepo.job.BATCH_SIZE
import com.tencent.bkrepo.job.SHARDING_COUNT
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import java.time.LocalDateTime
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.util.function.Consumer

@Component
class NodeCommonUtils(
    mongoTemplate: MongoTemplate,
) {
    init {
        Companion.mongoTemplate = mongoTemplate
    }

    data class Node(
        val id: String,
        val projectId: String,
        val repoName: String,
        val fullPath: String,
        val sha256: String,
        val size: Long,
        val lastAccessDate: LocalDateTime? = null,
        val archived: Boolean?,
    )

    companion object {
        lateinit var mongoTemplate: MongoTemplate
        private const val COLLECTION_NAME_PREFIX = "node_"
        fun findNodes(query: Query, storageCredentialsKey: String?): List<Node> {
            val nodes = mutableListOf<Node>()
            (0 until SHARDING_COUNT).map { "$COLLECTION_NAME_PREFIX$it" }.forEach { collection ->
                val find = mongoTemplate.find(query, Node::class.java, collection).filter {
                    val repo = RepositoryCommonUtils.getRepositoryDetail(it.projectId, it.repoName)
                    repo.storageCredentials?.key == storageCredentialsKey
                }
                nodes.addAll(find)
            }
            return nodes
        }

        fun forEachNode(query: Query, batchSize: Int = BATCH_SIZE, consumer: Consumer<Map<String, Any?>>) {
            var querySize: Int
            var lastId = ObjectId(MIN_OBJECT_ID)
            (0 until SHARDING_COUNT).map { "$COLLECTION_NAME_PREFIX$it" }.forEach { collection ->
                do {
                    val newQuery = Query.of(query)
                        .addCriteria(Criteria.where(ID).gt(lastId))
                        .limit(batchSize)
                        .with(Sort.by(ID).ascending())
                    val data = mongoTemplate.find<Map<String, Any?>>(
                        newQuery,
                        collection,
                    )
                    if (data.isEmpty()) {
                        break
                    }
                    data.forEach { consumer.accept(it) }
                    querySize = data.size
                    lastId = data.last()[ID] as ObjectId
                } while (querySize == batchSize)
            }
        }

        fun collectionNames(projectIds: List<String>): List<String> {
            val collectionNames = mutableListOf<String>()
            if (projectIds.isNotEmpty()) {
                projectIds.forEach {
                    val index = HashShardingUtils.shardingSequenceFor(it, SHARDING_COUNT)
                    collectionNames.add("${COLLECTION_NAME_PREFIX}$index")
                }
            } else {
                (0 until SHARDING_COUNT).forEach { collectionNames.add("${COLLECTION_NAME_PREFIX}$it") }
            }
            return collectionNames
        }
    }
}
