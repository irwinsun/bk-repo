package com.tencent.bkrepo.rpm.servcie

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactSearchContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactTransferContext
import com.tencent.bkrepo.common.artifact.repository.context.RepositoryHolder
import com.tencent.bkrepo.common.security.permission.Permission
import com.tencent.bkrepo.rpm.artifact.RpmArtifactInfo
import com.tencent.bkrepo.rpm.artifact.repository.RpmLocalRepository
import org.springframework.stereotype.Service

@Service
class RpmDebugService {
    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun flushRepomd(rpmArtifactInfo: RpmArtifactInfo) {
        val context = ArtifactSearchContext()
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        (repository as RpmLocalRepository).flushRepoMdXML(context, null)
    }

    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun flushAllRepomd(rpmArtifactInfo: RpmArtifactInfo) {
        val context = ArtifactTransferContext()
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        (repository as RpmLocalRepository).flushAllRepoData(context)
    }
}