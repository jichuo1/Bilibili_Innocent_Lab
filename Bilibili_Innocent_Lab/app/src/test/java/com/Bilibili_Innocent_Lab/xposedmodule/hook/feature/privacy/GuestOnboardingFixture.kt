package com.bilibili.pegasus.components.interest

class NewUserInterestChoosePlugin {
    var requests = 0
    var dialogs = 0
    var loading = true
    var submittedChoice = false
    var completed = 0
    fun E() { requests++ }
    fun F(interest: com.bilibili.pegasus.data.interestchoose.InterestChoose?) { dialogs++ }
    fun B(fromConfirm: Boolean, choice: bn3.h?) {
        loading = false
        submittedChoice = fromConfirm || choice != null
        completed++
    }
}

class LowActiveInterestChoosePlugin {
    var dialogs = 0
    var loading = true
    var submittedChoice = false
    fun C(interest: com.bilibili.pegasus.data.interestchoose.InterestChoose?) { dialogs++ }
    fun A(fromConfirm: Boolean, choice: bn3.h?, realtime: Boolean) {
        loading = false
        submittedChoice = fromConfirm || choice != null || realtime
    }
}
