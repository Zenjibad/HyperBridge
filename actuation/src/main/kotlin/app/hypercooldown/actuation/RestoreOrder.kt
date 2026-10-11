package app.hypercooldown.actuation

/**
 * The order a restore puts fields back in, when the options ever touch a field wider than sound and
 * vibration.
 *
 * AOSP's damping never touches importance or visibility. They are options here, and they are the
 * reason this file exists: if the process dies between lowering importance and restoring it,
 * reconciliation must put **importance back first**, before any other field, because it has the
 * widest blast radius and because a channel left at reduced importance has no owner to fix it --
 * invariant 1's exact failure mode, applied to a field that changes how the app appears device-wide.
 *
 * Importance **is** realised now (`HeadsUpRealisation`, `EditFactory.demotedImportance`), and the rule
 * is honoured in the shape the actuator actually has rather than by a runtime ordering:
 *  - the value goes back inside the **same single** `ChannelEdit` as every other field, so there is no
 *    window in which some fields are restored and importance is not;
 *  - the *lock bit* is released afterwards, by `unlockNotificationChannel` (transaction 43), and that
 *    order is the opposite of an intuition — see `ChannelActuator.restore`, where it is write first,
 *    unlock second, because the write is what sets the bit.
 *
 * So nothing calls [fields] at runtime, and that is stated rather than hidden: it remains the pin for
 * the case a locked field is ever written *separately*, which is the only case in which an ordering
 * between field writes can exist at all. Writing the rule as a comment in the actuator would have made
 * it untestable prose; writing it here makes it a pin.
 */
object RestoreOrder {

    /**
     * The fields to restore, in the order they must be written. Sound and vibration are always listed
     * last: the actuator is what decides whether either needs a write at all, and a restore that named
     * no field would be a restore that restores nothing.
     */
    fun fields(importanceTouched: Boolean, visibilityTouched: Boolean): List<String> = buildList {
        if (importanceTouched) add("importance")
        if (visibilityTouched) add("lockscreenVisibility")
        add("sound")
        add("vibration")
    }
}
