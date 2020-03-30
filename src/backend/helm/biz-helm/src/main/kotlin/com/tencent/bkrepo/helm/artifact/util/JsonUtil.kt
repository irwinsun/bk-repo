package com.tencent.bkrepo.helm.artifact.util

import com.google.gson.JsonParser
import com.tencent.bkrepo.helm.CHART_NOT_FOUND
import com.tencent.bkrepo.helm.CHART_VERSION_NOT_FOUND
import com.tencent.bkrepo.helm.EMPTY_CHART_OR_VERSION
import com.tencent.bkrepo.helm.EMPTY_NAME_OR_VERSION
import com.tencent.bkrepo.helm.ENTRIES
import com.tencent.bkrepo.helm.ERROR_NOT_FOUND
import com.tencent.bkrepo.helm.NO_CHART_NAME_FOUND
import org.apache.commons.lang.StringUtils
import java.io.File
import java.lang.NullPointerException

object JsonUtil {
    fun searchJson(indexYamlFile: File, urls: String):String {
        val jsonParser = JsonParser()
        val jsonStr = YamlUtil.yaml2Json(indexYamlFile)
        val urlList = urls.removePrefix("/").split("/").filter { it.isNotBlank() }
        return when (urlList.size) {
            //Without name and version
            0 -> {
                val result = jsonParser.parse(jsonStr).asJsonObject
                    .getAsJsonObject(ENTRIES)
                    .toString()
                //index.yaml content maybe null
                if (StringUtils.equals(result, EMPTY_CHART_OR_VERSION))  CHART_NOT_FOUND else result
            }
            //query with name
            1 -> {
                val result = try {
                    jsonParser.parse(jsonStr).asJsonObject
                        .getAsJsonObject(ENTRIES)
                        .getAsJsonArray(urlList[0])
                        .toString()
                } catch (nullPointer: NullPointerException) {
                    CHART_NOT_FOUND
                }
                if(StringUtils.equals(result, EMPTY_NAME_OR_VERSION))  CHART_NOT_FOUND else result
            }
            //query with name and version
            2 -> {
                val result = try {
                    jsonParser.parse(jsonStr).asJsonObject.getAsJsonObject(ENTRIES)
                        .getAsJsonArray(urlList[0])
                        .filter { it.toString().contains("version\":\"${urlList[1]}") }
                        .toString()
                } catch (ie: IllegalStateException) {
                    NO_CHART_NAME_FOUND
                }
                if(StringUtils.equals(result, EMPTY_NAME_OR_VERSION)) String.format(CHART_VERSION_NOT_FOUND, urlList[0],urlList[1]) else result
            }
            else -> {
                ERROR_NOT_FOUND
            }
        }
    }
}
