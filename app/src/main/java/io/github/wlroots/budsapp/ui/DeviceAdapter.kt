package io.github.wlroots.budsapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
