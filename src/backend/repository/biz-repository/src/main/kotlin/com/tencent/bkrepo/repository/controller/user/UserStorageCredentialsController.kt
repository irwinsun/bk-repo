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

package com.tencent.bkrepo.repository.controller.user

import com.tencent.bkrepo.common.api.exception.SystemErrorException
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.security.permission.Principal
import com.tencent.bkrepo.common.security.permission.PrincipalType
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.common.storage.credentials.FileSystemCredentials
import com.tencent.bkrepo.common.storage.credentials.HDFSCredentials
import com.tencent.bkrepo.common.storage.credentials.InnerCosCredentials
import com.tencent.bkrepo.common.storage.credentials.S3Credentials
import com.tencent.bkrepo.common.storage.credentials.StorageCredentials
import com.tencent.bkrepo.repository.pojo.credendials.StorageCredentialsCreateRequest
import com.tencent.bkrepo.repository.service.repo.StorageCredentialService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Principal(PrincipalType.ADMIN)
@RestController
@RequestMapping("/api/storage/credentials")
class UserStorageCredentialsController(
    private val storageCredentialService: StorageCredentialService
) {

    @PostMapping
    fun create(
        @RequestAttribute userId: String,
        @RequestBody storageCredentialsCreateRequest: StorageCredentialsCreateRequest
    ): Response<Void> {
        storageCredentialService.create(userId, storageCredentialsCreateRequest)
        return ResponseBuilder.success()
    }

    @GetMapping
    fun list(@RequestParam("region", required = false) region: String?): Response<List<StorageCredentials>> {
        val storageCredentialsList = storageCredentialService.list(region).map {
            when (it) {
                is FileSystemCredentials, is HDFSCredentials -> it
                is InnerCosCredentials -> it.copy(secretId = "*", secretKey = "*")
                is S3Credentials -> it.copy(accessKey = "*", secretKey = "*")
                else -> throw SystemErrorException()
            }
        }
        return ResponseBuilder.success(storageCredentialsList)
    }

    @GetMapping("/default")
    fun default(): Response<StorageCredentials> {
        return ResponseBuilder.success(storageCredentialService.default())
    }
}
