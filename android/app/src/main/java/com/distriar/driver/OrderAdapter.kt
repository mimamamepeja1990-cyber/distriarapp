package com.distriar.driver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.distriar.driver.databinding.ItemOrderBinding

class OrderAdapter(
    private var orders: List<Order>,
    private val onDelivered: (Order) -> Unit,
    private val onIssue: (Order) -> Unit,
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    private var nextOrderId: Int? = null

    init {
        setHasStableIds(true)
    }

    fun updateOrders(newOrders: List<Order>, nextId: Int?) {
        val oldOrders = orders
        val oldNextId = nextOrderId
        val newNextId = nextId
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldOrders.size

            override fun getNewListSize(): Int = newOrders.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldOrders[oldItemPosition].id == newOrders[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldOrders[oldItemPosition]
                val newItem = newOrders[newItemPosition]
                val sameData = oldItem == newItem
                val oldHighlight = oldItem.id == oldNextId
                val newHighlight = newItem.id == newNextId
                return sameData && oldHighlight == newHighlight
            }
        })
        orders = newOrders
        nextOrderId = newNextId
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun getItemId(position: Int): Long = orders[position].id.toLong()

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position], nextOrderId, onDelivered, onIssue)
    }

    override fun getItemCount(): Int = orders.size

    class OrderViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: Order, nextId: Int?, onDelivered: (Order) -> Unit, onIssue: (Order) -> Unit) {
            val route = order.routeOrder?.let { "Orden #$it" } ?: ""
            val address = formatAddress(order)
            binding.orderTitle.text = "Pedido #${order.id} · ${formatOrderStatusLabel(order.status)}"
            binding.orderAddress.text = address
            binding.orderCustomer.text = order.userFullName?.takeIf { it.isNotBlank() } ?: "Cliente sin nombre"
            binding.orderRoute.text = route
            val issueSummary = formatLatestIssueSummary(order)
            if (issueSummary.isNullOrBlank()) {
                binding.orderIssue.visibility = View.GONE
                binding.orderIssue.text = ""
            } else {
                binding.orderIssue.visibility = View.VISIBLE
                binding.orderIssue.text = issueSummary
            }

            val isCompleted = orderIsDelivered(order) || normalizeOrderStatus(order.status) == "cancelado"
            if (isCompleted) {
                binding.btnDelivered.visibility = View.GONE
                binding.btnIssue.visibility = View.GONE
            } else {
                binding.btnDelivered.visibility = View.VISIBLE
                binding.btnIssue.visibility = View.VISIBLE
                binding.btnDelivered.setOnClickListener { onDelivered(order) }
                binding.btnIssue.setOnClickListener { onIssue(order) }
                binding.btnIssue.text = if ((order.closedAttempts ?: 0) > 0) "Incidencia / Cerrado" else "Incidencia"
            }

            val highlight = nextId != null && order.id == nextId
            binding.root.strokeWidth = if (highlight) 4 else 0
            binding.root.strokeColor = if (highlight) 0xFFFF6F00.toInt() else 0x00000000
        }
    }
}
