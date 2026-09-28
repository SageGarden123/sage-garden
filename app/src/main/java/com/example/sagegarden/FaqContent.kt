package com.example.sagegarden

import androidx.annotation.StringRes

// GENERATED from one source together with res/values/strings_faq.xml — keep the two in step.

/** FAQ topic headings, in display order. */
enum class FaqTopic(@StringRes val title: Int) {
    START(R.string.faq_topic_start),
    PLANTS(R.string.faq_topic_plants),
    MAP(R.string.faq_topic_map),
    CARE(R.string.faq_topic_care),
    IRRIGATION(R.string.faq_topic_irrigation),
    PHOTOS(R.string.faq_topic_photos),
    SHARING(R.string.faq_topic_sharing),
    WIDGET(R.string.faq_topic_widget),
    SAGE(R.string.faq_topic_sage),
    BACKUP(R.string.faq_topic_backup),
    PRIVACY(R.string.faq_topic_privacy),
    TROUBLE(R.string.faq_topic_trouble),
}

/**
 * Every FAQ entry, in display order within its topic. Each has a stable name so screens can
 * open exactly the right answer (FaqInfoButton, the "faq?entry=" route). [feature] hides an
 * entry while that feature is hidden (e.g. Advanced-only features in Basic mode); [page] is the
 * Settings page an "Open this setting" button takes you to.
 */
enum class Faq(val topic: FaqTopic, @StringRes val question: Int, @StringRes val answer: Int, val feature: Feature? = null, val page: SettingsPage? = null) {
    WHAT_IS(FaqTopic.START, R.string.faq_what_is_q, R.string.faq_what_is_a),
    HOME_TAB(FaqTopic.START, R.string.faq_home_tab_q, R.string.faq_home_tab_a),
    FIRST_PLANT(FaqTopic.START, R.string.faq_first_plant_q, R.string.faq_first_plant_a),
    BASIC_ADVANCED(FaqTopic.START, R.string.faq_basic_advanced_q, R.string.faq_basic_advanced_a, page = SettingsPage.APP),
    SET_ADDRESS(FaqTopic.START, R.string.faq_set_address_q, R.string.faq_set_address_a, page = SettingsPage.GARDEN),
    FIND_PLANT(FaqTopic.PLANTS, R.string.faq_find_plant_q, R.string.faq_find_plant_a),
    PLANT_IDS(FaqTopic.PLANTS, R.string.faq_plant_ids_q, R.string.faq_plant_ids_a),
    ZONES(FaqTopic.PLANTS, R.string.faq_zones_q, R.string.faq_zones_a, page = SettingsPage.GARDEN),
    AI_IDENTIFY(FaqTopic.PLANTS, R.string.faq_ai_identify_q, R.string.faq_ai_identify_a),
    SAGE_AUTOFILL(FaqTopic.PLANTS, R.string.faq_sage_autofill_q, R.string.faq_sage_autofill_a, feature = Feature.SAGE_CARE_FREQUENCIES),
    DELETE_PLANT(FaqTopic.PLANTS, R.string.faq_delete_plant_q, R.string.faq_delete_plant_a),
    GARDEN_CHECK(FaqTopic.PLANTS, R.string.faq_garden_check_q, R.string.faq_garden_check_a, feature = Feature.AUDIT_SCREEN),
    REAL_VS_CUSTOM(FaqTopic.MAP, R.string.faq_real_vs_custom_q, R.string.faq_real_vs_custom_a, feature = Feature.CUSTOM_MAP, page = SettingsPage.GARDEN),
    PLACE_PLANT(FaqTopic.MAP, R.string.faq_place_plant_q, R.string.faq_place_plant_a, feature = Feature.PLACE_ON_MAP),
    MAP_POSITION(FaqTopic.MAP, R.string.faq_map_position_q, R.string.faq_map_position_a),
    SUN_MAP(FaqTopic.MAP, R.string.faq_sun_map_q, R.string.faq_sun_map_a, feature = Feature.SUN_MAP),
    DUE_DATES(FaqTopic.CARE, R.string.faq_due_dates_q, R.string.faq_due_dates_a),
    TURN_ON_REMINDERS(FaqTopic.CARE, R.string.faq_turn_on_reminders_q, R.string.faq_turn_on_reminders_a, page = SettingsPage.REMINDERS),
    REMINDER_TIME(FaqTopic.CARE, R.string.faq_reminder_time_q, R.string.faq_reminder_time_a, page = SettingsPage.REMINDERS),
    REMINDERS_MISSING(FaqTopic.CARE, R.string.faq_reminders_missing_q, R.string.faq_reminders_missing_a, page = SettingsPage.REMINDERS),
    REPEAT_OVERDUE(FaqTopic.CARE, R.string.faq_repeat_overdue_q, R.string.faq_repeat_overdue_a, page = SettingsPage.REMINDERS),
    SEASONAL(FaqTopic.CARE, R.string.faq_seasonal_q, R.string.faq_seasonal_a, feature = Feature.SEASONAL_WATERING),
    FERTILISE_PRUNE(FaqTopic.CARE, R.string.faq_fertilise_prune_q, R.string.faq_fertilise_prune_a, feature = Feature.FERTILISE_PRUNE, page = SettingsPage.REMINDERS),
    WEATHER(FaqTopic.CARE, R.string.faq_weather_q, R.string.faq_weather_a, feature = Feature.WEATHER_AWARE_REMINDERS, page = SettingsPage.REMINDERS),
    FROST(FaqTopic.CARE, R.string.faq_frost_q, R.string.faq_frost_a, feature = Feature.WEATHER_AWARE_REMINDERS, page = SettingsPage.REMINDERS),
    PROGRESS_PHOTOS(FaqTopic.CARE, R.string.faq_progress_photos_q, R.string.faq_progress_photos_a, feature = Feature.PROGRESS_PHOTOS, page = SettingsPage.REMINDERS),
    IRRIGATION_SUPPORTED(FaqTopic.IRRIGATION, R.string.faq_irrigation_supported_q, R.string.faq_irrigation_supported_a, feature = Feature.TUYA_INTEGRATION, page = SettingsPage.IRRIGATION),
    CONNECT_TUYA(FaqTopic.IRRIGATION, R.string.faq_connect_tuya_q, R.string.faq_connect_tuya_a, feature = Feature.TUYA_INTEGRATION, page = SettingsPage.IRRIGATION),
    CONNECT_RACHIO(FaqTopic.IRRIGATION, R.string.faq_connect_rachio_q, R.string.faq_connect_rachio_a, feature = Feature.TUYA_INTEGRATION, page = SettingsPage.IRRIGATION),
    HISTORY_SYNC(FaqTopic.IRRIGATION, R.string.faq_history_sync_q, R.string.faq_history_sync_a, feature = Feature.TUYA_INTEGRATION, page = SettingsPage.IRRIGATION),
    WATER_COST(FaqTopic.IRRIGATION, R.string.faq_water_cost_q, R.string.faq_water_cost_a, feature = Feature.COST_WATER_TRACKING),
    MANUAL_SCHEDULE(FaqTopic.IRRIGATION, R.string.faq_manual_schedule_q, R.string.faq_manual_schedule_a),
    PHOTO_STORAGE(FaqTopic.PHOTOS, R.string.faq_photo_storage_q, R.string.faq_photo_storage_a, page = SettingsPage.PHOTOS),
    GROWTH_TIMELINE(FaqTopic.PHOTOS, R.string.faq_growth_timeline_q, R.string.faq_growth_timeline_a, feature = Feature.GROWTH_TIMELINES),
    EXTRA_PHOTOS(FaqTopic.PHOTOS, R.string.faq_extra_photos_q, R.string.faq_extra_photos_a, feature = Feature.EXTRA_PHOTOS),
    SHARE_GARDEN(FaqTopic.SHARING, R.string.faq_share_garden_q, R.string.faq_share_garden_a, feature = Feature.GARDEN_SHARING, page = SettingsPage.GARDEN),
    JOIN_GARDEN(FaqTopic.SHARING, R.string.faq_join_garden_q, R.string.faq_join_garden_a, feature = Feature.GARDEN_SHARING, page = SettingsPage.GARDEN),
    SWITCH_GARDEN(FaqTopic.SHARING, R.string.faq_switch_garden_q, R.string.faq_switch_garden_a, feature = Feature.GARDEN_SHARING),
    VIEW_VS_EDIT(FaqTopic.SHARING, R.string.faq_view_vs_edit_q, R.string.faq_view_vs_edit_a, feature = Feature.GARDEN_SHARING),
    WHAT_IS_SHARED(FaqTopic.SHARING, R.string.faq_what_is_shared_q, R.string.faq_what_is_shared_a, feature = Feature.GARDEN_SHARING),
    SYNC_SPEED(FaqTopic.SHARING, R.string.faq_sync_speed_q, R.string.faq_sync_speed_a, feature = Feature.GARDEN_SHARING),
    LEAVE_REMOVE(FaqTopic.SHARING, R.string.faq_leave_remove_q, R.string.faq_leave_remove_a, feature = Feature.GARDEN_SHARING, page = SettingsPage.GARDEN),
    DESKTOP_APP(FaqTopic.SHARING, R.string.faq_desktop_app_q, R.string.faq_desktop_app_a, page = SettingsPage.ABOUT),
    WIDGET_ADD(FaqTopic.WIDGET, R.string.faq_widget_add_q, R.string.faq_widget_add_a),
    WIDGET_STALE(FaqTopic.WIDGET, R.string.faq_widget_stale_q, R.string.faq_widget_stale_a),
    SAGE_WHAT(FaqTopic.SAGE, R.string.faq_sage_what_q, R.string.faq_sage_what_a, feature = Feature.SAGE_ASSISTANT),
    SAGE_LIMITS(FaqTopic.SAGE, R.string.faq_sage_limits_q, R.string.faq_sage_limits_a),
    BACKUP_OPTIONS(FaqTopic.BACKUP, R.string.faq_backup_options_q, R.string.faq_backup_options_a, page = SettingsPage.DATA),
    NEW_PHONE(FaqTopic.BACKUP, R.string.faq_new_phone_q, R.string.faq_new_phone_a, page = SettingsPage.DATA),
    CSV_FORMAT(FaqTopic.BACKUP, R.string.faq_csv_format_q, R.string.faq_csv_format_a, page = SettingsPage.DATA),
    RESET_GARDEN(FaqTopic.BACKUP, R.string.faq_reset_garden_q, R.string.faq_reset_garden_a, page = SettingsPage.DATA),
    DATA_STORED(FaqTopic.PRIVACY, R.string.faq_data_stored_q, R.string.faq_data_stored_a),
    WHO_RECEIVES(FaqTopic.PRIVACY, R.string.faq_who_receives_q, R.string.faq_who_receives_a),
    ACCOUNT(FaqTopic.PRIVACY, R.string.faq_account_q, R.string.faq_account_a),
    DELETE_DATA(FaqTopic.PRIVACY, R.string.faq_delete_data_q, R.string.faq_delete_data_a, page = SettingsPage.ABOUT),
    NOT_SYNCING(FaqTopic.TROUBLE, R.string.faq_not_syncing_q, R.string.faq_not_syncing_a, feature = Feature.GARDEN_SHARING),
    WRONG_HEMISPHERE(FaqTopic.TROUBLE, R.string.faq_wrong_hemisphere_q, R.string.faq_wrong_hemisphere_a, feature = Feature.SEASONAL_WATERING, page = SettingsPage.GARDEN),
    CONTACT(FaqTopic.TROUBLE, R.string.faq_contact_q, R.string.faq_contact_a, page = SettingsPage.ABOUT),
}
