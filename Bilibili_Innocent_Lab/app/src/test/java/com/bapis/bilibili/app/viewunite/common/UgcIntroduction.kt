package com.bapis.bilibili.app.viewunite.common

class DescV2(val text: String)

class UgcIntroduction(val descList: List<DescV2>) {
    fun getDesc(index: Int): DescV2 = descList[index]
}
