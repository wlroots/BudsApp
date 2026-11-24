package io.github.wlroots.budsapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.wlroots.budsapp.R
import io.github.wlroots.budsapp.databinding.ItemDeviceBinding

data class DeviceListItem(
    val mac: String,
    val name: String,
    val configName: String?,
    val modelId: String?,
    val status: String,
    val connected: Boolean,
    val known: Boolean,
)

class DeviceAdapter(
    private val onClick: (DeviceListItem) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = ArrayList<DeviceListItem>()

    fun submit(newItems: List<DeviceListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DeviceListItem) {
            binding.deviceName.text = item.name
            val subtitle = buildList {
                item.configName?.let { add(it) }
                item.modelId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                if (!item.known) add("Unrecognised")
            }.joinToString(" · ")
            binding.deviceSubtitle.text = subtitle.ifEmpty { item.mac }
            binding.deviceStatus.text = item.status

            val dotColor = if (item.connected) R.color.status_connected else R.color.status_idle
            val dot = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_status_dot)?.mutate()
            if (dot != null) {
                DrawableCompat.setTint(dot, ContextCompat.getColor(binding.root.context, dotColor))
            }
            binding.deviceStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            binding.deviceStatus.compoundDrawablePadding = (4 * binding.root.resources.displayMetrics.density).toInt()

            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
