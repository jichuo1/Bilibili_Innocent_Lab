package com.bilibili.ship.theseus.ugc.endpage.relatedrecommand
class RenamedMerger {
    companion object { @JvmStatic fun merge(a: List<*>, b: List<*>): List<*> = a + b }
}
class OtherMerger { fun merge(a: List<*>, b: List<*>): List<*> = a + b }
