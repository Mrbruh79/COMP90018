package com.example.blap.ui.screens.groups

data class GroupsActions(
    val onOpenCreateGroup: () -> Unit,
    val onBeginGroupSettings: () -> Unit,
    val onGroupNameChanged: (String) -> Unit,
    val onToggleGroupMember: (String) -> Unit,
    val onCreateGroup: () -> Unit,
    val onSaveGroupSettings: () -> Unit,
)
