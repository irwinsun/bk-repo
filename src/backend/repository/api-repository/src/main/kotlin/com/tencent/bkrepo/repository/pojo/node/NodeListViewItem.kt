package com.tencent.bkrepo.repository.pojo.node

import com.tencent.bkrepo.repository.util.NodeUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.apache.commons.io.FileUtils

/**
 * 用于浏览器列表查看节点信息
 *
 * @author: carrypan
 * @date: 2019/12/11
 */
data class NodeListViewItem(
    val name: String,
    val lastModifiedDate: String,
    val size: String,
    val folder: Boolean
) : Comparable<NodeListViewItem> {

    override fun compareTo(other: NodeListViewItem): Int {
        return if (this.folder && !other.folder) -1
        else if (!this.folder && other.folder) 1
        else this.name.compareTo(other.name)
    }

    companion object {
        private val formatters = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun from(nodeInfo: NodeInfo): NodeListViewItem {
            val normalizedName = if (nodeInfo.folder) nodeInfo.name + NodeUtils.FILE_SEPARATOR else nodeInfo.name
            val normalizedSize = if (nodeInfo.folder) "-" else FileUtils.byteCountToDisplaySize(nodeInfo.size)
            val localDateTime = LocalDateTime.parse(nodeInfo.lastModifiedDate, DateTimeFormatter.ISO_DATE_TIME)
            val lastModifiedDate = formatters.format(localDateTime)
            return NodeListViewItem(normalizedName, lastModifiedDate, normalizedSize, nodeInfo.folder)
        }
    }
}