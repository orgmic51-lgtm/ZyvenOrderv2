package me.orders;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class Order {
    public final UUID id;
    public final UUID owner;
    public final String ownerName;
    public final ItemStack item;   // template, amount always 1
    public int amount;             // total requested
    public final double price;     // price per item
    public int delivered;          // items delivered by sellers
    public int collected;          // delivered items the owner already took
    public final long created;
    public final long expires;

    public Order(UUID id, UUID owner, String ownerName, ItemStack item, int amount, double price,
                 int delivered, int collected, long created, long expires) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.item = item;
        this.amount = amount;
        this.price = price;
        this.delivered = delivered;
        this.collected = collected;
        this.created = created;
        this.expires = expires;
    }

    public int remaining() {
        return Math.max(0, amount - delivered);
    }

    public int ready() {
        return delivered - collected;
    }
}
