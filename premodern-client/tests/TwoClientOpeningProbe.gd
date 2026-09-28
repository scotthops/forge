extends SceneTree

# Headless scene probe. Run twice with distinct --username values against the
# two-human Sligh host. It uses the same rendered controls a player would click.
const TIMEOUT_MSEC := 90000


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
	var prompt := main.get_node("%InteractionText") as Label
	var hand := main.get_node("%YourHand") as Container
	var local_header := main.get_node("%YourHeader") as Button
	var opponent_header := main.get_node("%OpponentHeader") as Button
	var turn := main.get_node("%StackTurnText") as Label
	var deadline := Time.get_ticks_msec() + TIMEOUT_MSEC
	var played := false
	var kept := false
	var opening_seen := false

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
		if kept and turn.text.begins_with("TURN 1 "):
			print("PROBE_ORDINARY_TURN title=", main.get_window().title, " turn=", turn.text)
			quit(0)
			return

	push_error("PROBE_FAIL timeout opening_seen=%s kept=%s" % [opening_seen, kept])
	quit(1)
