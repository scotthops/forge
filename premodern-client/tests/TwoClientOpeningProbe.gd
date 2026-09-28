extends SceneTree

# Headless scene probe. Run twice with distinct --username values against the
# two-human Sligh host. It uses the same rendered controls a player would click.
const TIMEOUT_MSEC := 120000


func _initialize() -> void:
	call_deferred("_run")


func _run() -> void:
	var scene := load("res://Main.tscn") as PackedScene
	if scene == null:
		push_error("PROBE_FAIL Main.tscn could not be loaded")
		quit(1)
		return
	var main := scene.instantiate()
	root.add_child(main)
	current_scene = main
	var ok := main.get_node("%OkButton") as Button
	var cancel := main.get_node("%CancelButton") as Button
	var pass_priority := main.get_node("%PassPriority") as Button
	var prompt := main.get_node("%InteractionText") as Label
	var hand := main.get_node("%YourHand") as Container
	var own_lands := main.get_node("%YourLands") as Container
	var opponent_lands := main.get_node("%OpponentLands") as Container
	var local_header := main.get_node("%YourHeader") as Button
	var opponent_header := main.get_node("%OpponentHeader") as Button
	var turn := main.get_node("%StackTurnText") as Label
	var deadline := Time.get_ticks_msec() + TIMEOUT_MSEC
	var played := false
	var kept := false
	var opening_seen := false
	var turn_one_seen := false
	var pass_count := 0
	var through_turn_two := "--through-turn-two" in OS.get_cmdline_user_args()
	var play_land := "--play-land" in OS.get_cmdline_user_args()
	var land_click_sent := false
	var hand_before_land := -1
	var last_opponent_hand_count := -1

	while Time.get_ticks_msec() < deadline:
		await process_frame
		if not opening_seen and hand.get_child_count() == 7 \
				and "Hand: 7" in local_header.text \
				and "Hand: 7" in opponent_header.text:
			opening_seen = true
			print("PROBE_OPENING_HAND own_cards=7 opponent_count=7 title=", main.get_window().title)
		if not played and not ok.disabled and ok.text == "Play" \
				and cancel.text == "Draw" and not prompt.text.is_empty():
			played = true
			print("PROBE_CHOICE Play title=", main.get_window().title)
			ok.pressed.emit()
		if not kept and opening_seen and not ok.disabled and ok.text == "Keep" \
				and cancel.text == "Mulligan" and not prompt.text.is_empty():
			kept = true
			print("PROBE_CHOICE Keep title=", main.get_window().title)
			ok.pressed.emit()
		if kept and not turn_one_seen and turn.text.begins_with("TURN 1 "):
			turn_one_seen = true
			print("PROBE_ORDINARY_TURN title=", main.get_window().title, " turn=", turn.text)
			if not through_turn_two and not play_land:
				quit(0)
				return
		if play_land and turn_one_seen:
			var own_hand_count := _hand_count(local_header)
			var opponent_hand_count := _hand_count(opponent_header)
			if land_click_sent and _has_visible_card(own_lands) \
					and own_hand_count == hand_before_land - 1:
				print("PROBE_LAND_OWN before=", hand_before_land, " after=", own_hand_count,
					" title=", main.get_window().title)
				quit(0)
				return
			if _has_visible_card(opponent_lands) and last_opponent_hand_count > 0 \
					and opponent_hand_count == last_opponent_hand_count - 1:
				print("PROBE_LAND_OPPONENT before=", last_opponent_hand_count,
					" after=", opponent_hand_count, " title=", main.get_window().title)
				quit(0)
				return
			last_opponent_hand_count = opponent_hand_count
			if not land_click_sent and "YOUR TURN" in turn.text:
				var mountain := _selectable_mountain(hand)
				if mountain != null:
					land_click_sent = true
					hand_before_land = own_hand_count
					print("PROBE_LAND_CLICK before=", hand_before_land,
						" title=", main.get_window().title)
					mountain.emit_signal("PrimaryActionRequested")
		if (through_turn_two or play_land) and turn_one_seen and turn.text.begins_with("TURN 1 ") \
				and not land_click_sent and not pass_priority.disabled:
			pass_count += 1
			print("PROBE_PASS count=", pass_count, " title=", main.get_window().title)
			pass_priority.pressed.emit()
		if play_land and turn_one_seen and turn.text.begins_with("TURN 2 ") \
				and not land_click_sent and not pass_priority.disabled:
			pass_count += 1
			print("PROBE_PASS count=", pass_count, " title=", main.get_window().title)
			pass_priority.pressed.emit()
		if through_turn_two and not play_land and turn_one_seen and turn.text.begins_with("TURN 2 "):
			print("PROBE_TURN_TWO passes=", pass_count, " title=", main.get_window().title,
				" turn=", turn.text)
			quit(0)
			return

	push_error("PROBE_FAIL timeout opening_seen=%s kept=%s turn_one_seen=%s passes=%s" % [
		opening_seen, kept, turn_one_seen, pass_count])
	quit(1)


func _selectable_mountain(hand: Container) -> Node:
	for card in hand.get_children():
		if card.is_queued_for_deletion() or not card.has_signal("PrimaryActionRequested"):
			continue
		var label := card.get_node_or_null("%FallbackName") as Label
		if label != null and label.text == "Mountain" \
				and "Forge selectable" in (card as Control).tooltip_text:
			return card
	return null


func _has_visible_card(row: Container) -> bool:
	for card in row.get_children():
		if not card.is_queued_for_deletion() and card.has_signal("PrimaryActionRequested"):
			return true
	return false


func _hand_count(header: Button) -> int:
	var marker := "Hand: "
	var start := header.text.find(marker)
	if start < 0:
		return -1
	return header.text.substr(start + marker.length()).to_int()
