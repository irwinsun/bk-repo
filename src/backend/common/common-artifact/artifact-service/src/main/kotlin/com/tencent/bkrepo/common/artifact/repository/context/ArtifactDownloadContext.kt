package com.tencent.bkrepo.common.artifact.repository.context

import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail

/**
 * 构件下载context
 */
open class ArtifactDownloadContext(repo: RepositoryDetail? = null) : ArtifactContext(repo)
