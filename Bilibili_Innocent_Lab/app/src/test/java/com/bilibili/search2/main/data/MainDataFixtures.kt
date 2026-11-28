package com.bilibili.search2.main.data

import com.bilibili.search2.api.SearchSquareType

// From i.kt
class i(@JvmField val sections: List<SearchSquareType>?)

// From RenamedState.kt
class RenamedState(@JvmField val sections: List<SearchSquareType>)
class WrongState(@JvmField val values: List<String>)
