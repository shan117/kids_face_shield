package com.shantanu.shield.util

import android.content.pm.ApplicationInfo
import android.os.Build

enum class AppCategory(val label: String, val order: Int) {
    GAMING("Games", 0),
    ENTERTAINMENT("Entertainment", 1),
    SOCIAL("Social", 2),
    EDUCATION("Education", 3),
    PRODUCTIVITY("Productivity", 4),
    NEWS("News & Maps", 5),
    OTHER("Other", 6)
}

object AppCategorizer {

    // Education apps don't have a dedicated ApplicationInfo.category bucket, so we
    // rely on a hand-curated package list + a label regex. The list mixes global
    // (Duolingo, Khan Academy, Coursera) and India-focused (BYJU's, Unacademy,
    // Vedantu) apps because the target audience is mostly Indian families.
    private val EDUCATION_PACKAGES: Set<String> = setOf(
        "com.duolingo",
        "org.khanacademy.android",
        "com.byjus.thelearningapp",
        "com.byjus.thelearningapp.premium",
        "com.byjus.testprep",
        "org.brilliant.android",
        "org.coursera.android",
        "com.udemy.android",
        "com.microblink.photomath",
        "com.quizlet.quizletandroid",
        "com.memrise.android.memrisecompanion",
        "com.babbel.mobile.android.en",
        "com.vedantu.android",
        "com.unacademy.consumption.unacademyapp",
        "com.toppr.android",
        "com.whitehatjr.codingapp",
        "com.cuemath.live",
        "in.testbook.tbapp",
        "com.helloenglish.englishlearning",
        "us.nobarriers.elsa",
        "edu.mit.scratchjr",
        "org.edx.mobile",
        "com.skillshare.Skillshare",
        "com.tagheuer.golf",
        "com.google.android.apps.classroom",
        "com.microsoft.office.lens.lensactivity"
    )

    private val EDUCATION_LABEL_REGEX = Regex(
        "\\b(learn|lesson|tutor|education|study|academy|school|teacher|student|homework|grammar|vocabulary|spell|spelling)\\b",
        RegexOption.IGNORE_CASE
    )

    fun categoryOf(info: ApplicationInfo, label: String): AppCategory {
        // 1. Education heuristic FIRST. The Play-Store category for these apps is
        //    often PRODUCTIVITY or UNDEFINED, which would mis-route them, so we
        //    override using the known-package list + a label regex.
        if (info.packageName in EDUCATION_PACKAGES) return AppCategory.EDUCATION
        if (EDUCATION_LABEL_REGEX.containsMatchIn(label)) return AppCategory.EDUCATION

        // 2. ApplicationInfo.category (set by the Play Store / app manifest, API 26+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMING
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> AppCategory.ENTERTAINMENT
                ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_NEWS,
                ApplicationInfo.CATEGORY_MAPS -> AppCategory.NEWS
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
                else -> AppCategory.OTHER
            }
        }
        return AppCategory.OTHER
    }
}
