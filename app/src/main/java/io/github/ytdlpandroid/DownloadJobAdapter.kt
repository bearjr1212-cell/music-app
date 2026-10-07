package io.github.ytdlpandroid

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Maps a persisted queue state to its user-facing label. */
internal fun jobStateLabel(state: String): Int = when (state) {
    DownloadQueueStore.STATE_QUEUED -> R.string.state_queued
    DownloadQueueStore.STATE_RUNNING -> R.string.state_running
    DownloadQueueStore.STATE_PAUSED -> R.string.state_paused
    DownloadQueueStore.STATE_COMPLETE -> R.string.state_complete
    DownloadQueueStore.STATE_PARTIAL -> R.string.state_partial
    DownloadQueueStore.STATE_FAILED -> R.string.state_failed
    DownloadQueueStore.STATE_CANCELED -> R.string.state_canceled
    else -> R.string.state_queued
}

/**
 * Adapter for the download queue list (Requirement 5.3).
 *
 * Holds no Activity reference (Requirement 5.11); every interaction is routed through
 * [Callbacks], which `MainActivity` implements.
 */
internal class DownloadJobAdapter(
    private val callbacks: Callbacks,
) : ListAdapter<DownloadJob, DownloadJobAdapter.JobHolder>(DIFF) {

    interface Callbacks {
        fun onJobPause(jobId: Long)
        fun onJobResume(jobId: Long)
        fun onJobCancel(jobId: Long)
        fun onJobRetry(jobId: Long)
        fun onJobMoveToFront(jobId: Long)
        fun onJobDetails(job: DownloadJob)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobHolder =
        JobHolder(buildJobCard(parent.context), callbacks)

    override fun onBindViewHolder(holder: JobHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: JobHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // Requirement 5.9: a progress tick touches the text and bar only, so the action
        // buttons are not torn down and rebuilt several times a second.
        val job = getItem(position)
        when {
            payloads.contains(STATE_CHANGED) -> holder.bindStateChange(job)
            payloads.contains(PROGRESS_CHANGED) -> holder.bindProgress(job)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun buildJobCard(context: android.content.Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = context.dp(8)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                tag = TAG_DETAIL
                textSize = 13f
                setTextColor(COLOR_TEXT)
                setPadding(0, context.dp(4), context.dp(8), context.dp(4))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        header.addView(
            LinearLayout(context).apply {
                tag = TAG_ACTIONS
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = TAG_PROGRESS
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(6) },
        )
        return card
    }

    internal class JobHolder(
        card: LinearLayout,
        private val callbacks: Callbacks,
    ) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val detail = card.findViewWithTag<TextView>(TAG_DETAIL)
        private val actions = card.findViewWithTag<LinearLayout>(TAG_ACTIONS)
        private val progress = card.findViewWithTag<ProgressBar>(TAG_PROGRESS)

        fun bind(job: DownloadJob) {
            bindProgress(job)
            bindActions(job)
        }

        /** Full rebind plus the pop the old imperative renderer played on a state change. */
        fun bindStateChange(job: DownloadJob) {
            bind(job)
            itemView.animateStateChange()
        }

        fun bindProgress(job: DownloadJob) {
            detail.text = context.getString(
                R.string.queue_job_detail,
                job.id,
                context.getString(jobStateLabel(job.state)),
                job.percent?.let { context.getString(R.string.percent_suffix, it) }.orEmpty(),
            )
            val running = job.state == DownloadQueueStore.STATE_RUNNING
            val queued = job.state == DownloadQueueStore.STATE_QUEUED
            progress.visibility = if (running || queued) View.VISIBLE else View.GONE
            progress.isIndeterminate = running && job.percent == null
            progress.setProgress(job.percent?.coerceIn(0, 100) ?: 0, false)
        }

        private fun bindActions(job: DownloadJob) {
            actions.removeAllViews()
            val id = job.id
            when (job.state) {
                DownloadQueueStore.STATE_RUNNING -> {
                    addAction(R.string.pause) { callbacks.onJobPause(id) }
                    addAction(R.string.action_cancel) { callbacks.onJobCancel(id) }
                }

                DownloadQueueStore.STATE_QUEUED -> {
                    addAction(R.string.move_job_to_front) { callbacks.onJobMoveToFront(id) }
                    addAction(R.string.action_cancel) { callbacks.onJobCancel(id) }
                }

                DownloadQueueStore.STATE_PAUSED -> {
                    addAction(R.string.resume) { callbacks.onJobResume(id) }
                    addAction(R.string.action_cancel) { callbacks.onJobCancel(id) }
                }

                DownloadQueueStore.STATE_FAILED,
                DownloadQueueStore.STATE_PARTIAL,
                DownloadQueueStore.STATE_CANCELED,
                -> {
                    addAction(R.string.download_details) { callbacks.onJobDetails(job) }
                    addAction(R.string.action_retry) { callbacks.onJobRetry(id) }
                }
            }
        }

        private fun addAction(label: Int, onClick: () -> Unit) {
            actions.addView(
                Button(context).apply {
                    text = context.getString(label)
                    context.styleActionButton(this, false)
                    setOnClickListener { onClick() }
                },
            )
        }
    }

    private companion object {
        const val TAG_DETAIL = "job-detail"
        const val TAG_ACTIONS = "job-actions"
        const val TAG_PROGRESS = "job-progress"

        /** Payload markers for Requirement 5.9 partial rebinds. */
        val STATE_CHANGED = Any()
        val PROGRESS_CHANGED = Any()

        val DIFF = object : DiffUtil.ItemCallback<DownloadJob>() {
            override fun areItemsTheSame(oldItem: DownloadJob, newItem: DownloadJob): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadJob, newItem: DownloadJob): Boolean =
                oldItem == newItem

            // Only reached when the contents differ, so the fallback is a progress/message tick.
            override fun getChangePayload(oldItem: DownloadJob, newItem: DownloadJob): Any =
                if (oldItem.state != newItem.state) STATE_CHANGED else PROGRESS_CHANGED
        }
    }
}
