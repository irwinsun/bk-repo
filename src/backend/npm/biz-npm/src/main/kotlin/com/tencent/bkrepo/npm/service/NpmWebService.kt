package com.tencent.bkrepo.npm.service

import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.npm.artifact.NpmArtifactInfo
import com.tencent.bkrepo.npm.pojo.PackageInfoResponse
import com.tencent.bkrepo.npm.pojo.user.NpmPackageInfo
import com.tencent.bkrepo.npm.pojo.user.NpmPackageVersionInfo
import com.tencent.bkrepo.npm.pojo.user.PackageDeleteRequest

interface NpmWebService {

    /**
     * 查询npm包的信息
     */
    fun queryPackageInfo(artifactInfo: NpmArtifactInfo): PackageInfoResponse

    /**
     *  查询npm包列表（可根据名称或者状态进行查询）
     */
    fun queryPkgList(
        userId: String?,
        artifactInfo: NpmArtifactInfo,
        pageNumber: Int,
        pageSize: Int,
        name: String?,
        stageTag: String?
    ): Page<NpmPackageInfo>

    /**
     * 根据包名称获取版本列表
     */
    fun queryPkgVersionList(
        userId: String?,
        artifactInfo: NpmArtifactInfo,
        pageNumber: Int,
        pageSize: Int,
        name: String
    ): Page<NpmPackageVersionInfo>

    /**
     * 删除包
     */
    fun deletePackage(deleteRequest: PackageDeleteRequest)
}
