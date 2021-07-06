/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.replication.job.replicator

import com.tencent.bkrepo.common.artifact.stream.rateLimit
import com.tencent.bkrepo.replication.job.ReplicaContext
import com.tencent.bkrepo.replication.mapping.PackageNodeMappings
import com.tencent.bkrepo.replication.pojo.blob.InputStreamMultipartFile
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.packages.PackageSummary
import com.tencent.bkrepo.repository.pojo.packages.PackageVersion
import org.springframework.stereotype.Component

/**
 * 边缘节点数据同步实现类
 * 中心节点 同步到 边缘节点 的同步实现类
 */
@Component
class EdgeNodeReplicator : ScheduledReplicator() {

    override fun checkVersion(context: ReplicaContext) {
        // do nothing
    }

    override fun replicaProject(context: ReplicaContext) {
        // do nothing
    }

    override fun replicaRepo(context: ReplicaContext) {
        // do nothing
    }

    override fun replicaPackage(context: ReplicaContext, packageSummary: PackageSummary) {
        // do nothing
    }

    override fun replicaDir(context: ReplicaContext, node: NodeInfo) {
        // do nothing
    }

    override fun replicaPackageVersion(
        context: ReplicaContext,
        packageSummary: PackageSummary,
        packageVersion: PackageVersion
    ): Boolean {
        with(context) {
            var affected = false
            // 文件数据
            PackageNodeMappings.map(
                type = localRepoType,
                key = packageSummary.key,
                version = packageVersion.name,
                extension = packageVersion.extension
            ).forEach {
                val node = localDataManager.findNodeDetail(
                    projectId = localProjectId,
                    repoName = localRepoName,
                    fullPath = it
                )
                if (replicaFile(context, node.nodeInfo)) {
                    affected = true
                }
            }
            return affected
        }
    }

    override fun replicaFile(context: ReplicaContext, node: NodeInfo): Boolean {
        with(context) {
            val sha256 = node.sha256.orEmpty()
            val artifactInputStream = localDataManager.getBlobData(sha256, node.size, localRepo)
            val rateLimitInputStream = artifactInputStream.rateLimit(replicationProperties.rateLimit.toBytes())
            val file = InputStreamMultipartFile(rateLimitInputStream, node.size)
            if (blobReplicaClient.check(sha256).data != true) {
                blobReplicaClient.push(file = file, sha256 = sha256)
                return true
            }
            return false
        }
    }
}
