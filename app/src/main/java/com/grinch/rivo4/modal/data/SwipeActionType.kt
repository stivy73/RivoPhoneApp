package com.grinch.rivo4.modal.data

import com.grinch.rivo4.R

/** IDs match the existing persisted PreferenceManager.SWIPE_ACTION_* values. */
enum class SwipeActionType(val id: Int, val titleRes: Int) {
    NONE(0, R.string.swipe_action_none), CALL(1, R.string.swipe_action_call),
    MESSAGE(2, R.string.swipe_action_message), VIDEO_CALL(3, R.string.swipe_action_video_call),
    WHATSAPP(4, R.string.swipe_action_whatsapp), COPY_NUMBER(5, R.string.swipe_action_copy_number),
    DELETE(6, R.string.swipe_action_delete);
    companion object { fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: NONE }
}
