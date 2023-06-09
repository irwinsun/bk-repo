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

package com.tencent.bkrepo.auth.service.impl

import com.tencent.bkrepo.auth.message.AuthMessageCode
import com.tencent.bkrepo.auth.model.TProxy
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.proxy.ProxyCreateRequest
import com.tencent.bkrepo.auth.pojo.proxy.ProxyInfo
import com.tencent.bkrepo.auth.pojo.proxy.ProxyKey
import com.tencent.bkrepo.auth.pojo.proxy.ProxyListOption
import com.tencent.bkrepo.auth.pojo.proxy.ProxyStatus
import com.tencent.bkrepo.auth.pojo.proxy.ProxyStatusRequest
import com.tencent.bkrepo.auth.pojo.proxy.ProxyUpdateRequest
import com.tencent.bkrepo.auth.repository.ProxyRepository
import com.tencent.bkrepo.auth.service.ProxyService
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.util.Preconditions
import com.tencent.bkrepo.common.mongo.dao.util.Pages
import com.tencent.bkrepo.common.security.manager.PermissionManager
import com.tencent.bkrepo.common.security.util.AESUtils
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import net.bytebuddy.utility.RandomString
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import kotlin.random.Random

@Service
class ProxyServiceImpl(
    private val proxyRepository: ProxyRepository,
    private val permissionManager: PermissionManager
) : ProxyService {
    override fun create(request: ProxyCreateRequest): ProxyInfo {
        permissionManager.checkProjectPermission(PermissionAction.MANAGE, request.projectId)
        val userId = SecurityUtils.getUserId()
        var name = RandomString.make(6)
        while (checkExist(request.projectId, name)) {
            name = RandomString.make(6)
        }
        val secretKey = AESUtils.encrypt(RandomString.make(32))
        val tProxy = TProxy(
            name = name,
            displayName = request.displayName,
            projectId = request.projectId,
            clusterName = request.clusterName,
            ip = StringPool.UNKNOWN,
            secretKey = secretKey,
            sessionKey = StringPool.EMPTY,
            ticket = Random.nextInt(),
            ticketCreateInstant = Instant.now(),
            createdBy = userId,
            createdDate = LocalDateTime.now(),
            lastModifiedBy = userId,
            lastModifiedDate = LocalDateTime.now(),
            status = ProxyStatus.CREATE
        )
        return proxyRepository.insert(tProxy).convert()
    }

    override fun getInfo(projectId: String, name: String): ProxyInfo {
        val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
        permissionManager.checkProjectPermission(PermissionAction.READ, tProxy.projectId)
        return tProxy.convert()
    }

    override fun page(projectId: String, option: ProxyListOption): Page<ProxyInfo> {
        permissionManager.checkProjectPermission(PermissionAction.READ, projectId)
        val pageRequest = Pages.ofRequest(option.pageNumber, option.pageSize)
        val page = proxyRepository.findByOption(projectId, option)
        return Pages.ofResponse(pageRequest, page.totalElements, page.content.map { it.convert() })
    }

    override fun getEncryptedKey(projectId: String, name: String): ProxyKey {
        permissionManager.checkProjectPermission(PermissionAction.READ, projectId)
        val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
        return ProxyKey(tProxy.secretKey, tProxy.sessionKey)
    }

    override fun update(request: ProxyUpdateRequest): ProxyInfo {
        val userId = SecurityUtils.getUserId()
        val tProxy = proxyRepository.findByProjectIdAndName(request.projectId, request.name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, request.name)
        permissionManager.checkProjectPermission(PermissionAction.MANAGE, tProxy.projectId)
        request.displayName?.let { tProxy.displayName = it }
        request.ip?.let { tProxy.ip = it }
        tProxy.lastModifiedBy = userId
        tProxy.lastModifiedDate = LocalDateTime.now()
        return proxyRepository.save(tProxy).convert()
    }

    override fun delete(projectId: String, name: String) {
        permissionManager.checkProjectPermission(PermissionAction.MANAGE, projectId)
        proxyRepository.findByProjectIdAndName(projectId, name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
        proxyRepository.deleteByProjectIdAndName(projectId, name)
    }

    override fun ticket(projectId: String, name: String): Int {
        val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)

        return if (!tProxy.ticketCreateInstant.plusSeconds(15).isAfter(Instant.now())) {
            val ticket = Random.nextInt()
            tProxy.ticket = ticket
            tProxy.ticketCreateInstant = Instant.now()
            proxyRepository.save(tProxy)
            ticket
        } else {
            tProxy.ticket
        }
    }

    override fun startup(request: ProxyStatusRequest): String {
        with(request) {
            val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
                ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
            val secretKey = AESUtils.decrypt(tProxy.secretKey)
            Preconditions.checkArgument(
                expression = tProxy.ticketCreateInstant.plusSeconds(N_EXPIRED_SEC).isAfter(Instant.now()),
                name = TProxy::ticket.name
            )
            Preconditions.checkArgument(
                expression = AESUtils.encrypt("$name:$STARTUP_OPERATION:${tProxy.ticket}", secretKey) == message,
                name = message
            )
            val sessionKey = AESUtils.encrypt(RandomString.make(32), secretKey)
            tProxy.status = ProxyStatus.ONLINE
            tProxy.sessionKey = sessionKey
            tProxy.ip = HttpContextHolder.getClientAddress()
            proxyRepository.save(tProxy)
            return sessionKey
        }
    }

    override fun shutdown(request: ProxyStatusRequest) {
        with(request) {
            val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
                ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
            val secretKey = AESUtils.decrypt(tProxy.secretKey)
            Preconditions.checkArgument(
                expression = tProxy.ticketCreateInstant.plusSeconds(N_EXPIRED_SEC).isAfter(Instant.now()),
                name = TProxy::ticket.name
            )
            Preconditions.checkArgument(
                expression = AESUtils.encrypt("$name:$SHUTDOWN_OPERATION:${tProxy.ticket}", secretKey) == message,
                name = ProxyStatusRequest::message.name
            )
            tProxy.status = ProxyStatus.OFFLINE
            tProxy.sessionKey = StringPool.EMPTY
            proxyRepository.save(tProxy)
        }
    }

    override fun heartbeat(projectId: String, name: String) {
        val tProxy = proxyRepository.findByProjectIdAndName(projectId, name)
            ?: throw ErrorCodeException(AuthMessageCode.AUTH_PROXY_NOT_EXIST, name)
        tProxy.heartbeatTime = LocalDateTime.now()
        proxyRepository.save(tProxy)
    }

    private fun checkExist(projectId: String, name: String): Boolean {
        return proxyRepository.findByProjectIdAndName(projectId, name) != null
    }

    fun TProxy.convert() = ProxyInfo(
        name = name,
        displayName = displayName,
        projectId = projectId,
        clusterName = clusterName,
        ip = ip,
        status = status
    )

    companion object {
        private val logger = LoggerFactory.getLogger(ProxyServiceImpl::class.java)
        private const val N_EXPIRED_SEC = 30L
        private const val STARTUP_OPERATION = "startup"
        private const val SHUTDOWN_OPERATION = "shutdown"
    }
}
