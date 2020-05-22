package com.tencent.bkrepo.common.artifact.auth.jwt

import com.tencent.bkrepo.common.artifact.auth.core.AuthCredentials

data class JwtAuthCredentials(val token: String) : AuthCredentials()