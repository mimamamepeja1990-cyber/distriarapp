package com.distriar.driver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.distriar.driver.databinding.ItemOrderBinding

class OrderAdapter(
    private var orders: List<Order>,
    private val onDelivered: (Order) -> Unit,
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    private var nextOrderId: Int? = null

    fun updateOrders(newOrders: List<Order>, nextId: Int?) {
        orders = newOrders
        nextOrderId = nextId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position], nextOrderId, onDelivered)
    }

    override fun getItemCount(): Int = orders.size

    class OrderViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: Order, nextId: Int?, onDelivered: (Order) -> Unit) {
            val status = order.status ?: ""
            val route = order.routeOrder?.let { "Orden #$it" } ?: ""
            val address = formatAddress(order)
            binding.orderTitle.text = "Pedido #${order.id} · ${status.uppercase()}"
            binding.orderAddress.text = address
            binding.orderCustomer.text = order.userFullName?.takeIf { it.isNotBlank() } ?: "Cliente sin nombre"
            binding.orderRoute.text = route

            val isDelivered = orderIsDelivered(order)
            if (isDelivered) {
                binding.btnDelivered.visibility = View.GONE
            } else {
                binding.btnDelivered.visibility = View.VISIBLE
                binding.btnDelivered.setOnClickListener { onDelivered(order) }
            }

            val highlight = nextId != null && order.id == nextId
            binding.root.strokeWidth = if (highlight) 4 else 0
            binding.root.strokeColor = if (highlight) 0xFFFF6F00.toInt() else 0x00000000
        }
    }
}
