package com.tencent.bkrepo.docker.util

class Digest {

    companion object {

        private var algorithms = listOf<String>("SHA256", "SHA384", "SHA512")

        fun validDigest(data: String): Boolean {
            var digArray = data.split(":")
            if (algorithms.contains(digArray[0])) {
                return true
            }
            return false
        }
    }
}
