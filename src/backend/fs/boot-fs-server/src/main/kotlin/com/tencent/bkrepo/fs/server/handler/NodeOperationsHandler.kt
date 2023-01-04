/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
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

package com.tencent.bkrepo.fs.server.handler

import com.tencent.bkrepo.fs.server.FS_ATTR_KEY
import com.tencent.bkrepo.fs.server.api.RRepositoryClient
import com.tencent.bkrepo.fs.server.model.NodeAttribute
import com.tencent.bkrepo.fs.server.model.NodeAttribute.Companion.DEFAULT_MODE
import com.tencent.bkrepo.fs.server.request.ChangeAttributeRequest
import com.tencent.bkrepo.fs.server.request.NodePageRequest
import com.tencent.bkrepo.fs.server.request.NodeRequest
import com.tencent.bkrepo.fs.server.toNode
import com.tencent.bkrepo.fs.server.utils.ReactiveResponseBuilder
import com.tencent.bkrepo.fs.server.utils.ReactiveSecurityUtils
import com.tencent.bkrepo.repository.pojo.metadata.MetadataModel
import com.tencent.bkrepo.repository.pojo.metadata.MetadataSaveRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait

/**
 * 节点操作相关的处理器
 *
 * 处理节点操作的请求
 * */
class NodeOperationsHandler(private val rRepositoryClient: RRepositoryClient) {

    suspend fun getNode(request: ServerRequest): ServerResponse {
        with(NodeRequest(request)) {
            val nodeDetail = rRepositoryClient.getNodeDetail(
                projectId = projectId,
                repoName = repoName,
                fullPath = fullPath
            ).awaitSingle().data ?: return ServerResponse.notFound().buildAndAwait()
            return ReactiveResponseBuilder.success(nodeDetail.nodeInfo.toNode())
        }
    }

    suspend fun listNodes(request: ServerRequest): ServerResponse {
        val pageRequest = NodePageRequest(request)
        with(pageRequest) {
            val nodes = rRepositoryClient.listNodePage(
                path = fullPath,
                projectId = projectId,
                repoName = repoName,
                option = listOption
            ).awaitSingle().data?.records?.map { it.toNode() }?.toList()
                ?: return ServerResponse.notFound().buildAndAwait()
            return ReactiveResponseBuilder.success(nodes)
        }
    }

    /**
     * 删除节点
     * */
    suspend fun deleteNode(request: ServerRequest): ServerResponse {
        with(NodeRequest(request)) {
            val nodeDeleteRequest = NodeDeleteRequest(
                projectId = projectId,
                repoName = repoName,
                fullPath = fullPath,
                operator = ReactiveSecurityUtils.getUser()
            )
            rRepositoryClient.deleteNode(nodeDeleteRequest).awaitSingle()
            return ReactiveResponseBuilder.success()
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun changeAttribute(request: ServerRequest): ServerResponse {
        with(ChangeAttributeRequest(request)) {
            val preFsAttributeStr = rRepositoryClient.listMetadata(projectId, repoName, fullPath).awaitSingle()?.data
                ?.get(FS_ATTR_KEY)
            val attrMap = preFsAttributeStr as? Map<String, Any> ?: mapOf()
            val preFsAttribute = NodeAttribute(
                owner = attrMap[NodeAttribute::owner.name] as? String ?: ReactiveSecurityUtils.getUser(),
                mode = attrMap[NodeAttribute::mode.name] as? Int ?: DEFAULT_MODE
            )

            val attributes = NodeAttribute(
                owner = owner ?: preFsAttribute.owner,
                mode = mode ?: preFsAttribute.mode
            )
            val fsAttr = MetadataModel(
                key = FS_ATTR_KEY,
                value = attributes
            )
            val saveMetaDataRequest = MetadataSaveRequest(
                projectId = projectId,
                repoName = repoName,
                fullPath = fullPath,
                nodeMetadata = listOf(fsAttr),
                operator = ReactiveSecurityUtils.getUser()
            )
            rRepositoryClient.saveMetadata(saveMetaDataRequest).awaitSingle()
            return ReactiveResponseBuilder.success(attributes)
        }
    }

    suspend fun getStat(request: ServerRequest): ServerResponse {
        with(NodeRequest(request)) {
            val nodeStat = rRepositoryClient.computeSize(projectId, repoName, fullPath).awaitSingle().data
            return ReactiveResponseBuilder.success(nodeStat)
        }
    }
}
