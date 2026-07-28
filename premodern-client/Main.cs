#nullable enable

using Godot;
using PremodernClient;
using PremodernClient.Bridge;
using PremodernClient.Protocol;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

public partial class Main : Control
{
	[ExportGroup("Forge Bridge")]
	[Export] public string JavaExecutable { get; set; } = "java";
	[Export(PropertyHint.File, "*.jar")]
	public string BridgeJarPath { get; set; }
		= "../forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar";
	[Export(PropertyHint.Dir)] public string AssetsDirectory { get; set; } = "../forge-gui";
	[Export] public string Host { get; set; } = "localhost";
	[Export(PropertyHint.Range, "1,65535,1")] public int Port { get; set; } = 36743;
	[Export] public string Username { get; set; } = "Godot Bridge Client";

	private readonly BridgeClientState clientState = new();
	private static readonly Regex LondonMulliganPrompt = new(
		@"^Return\s+(?<remaining>\d+)\s+card\(s\)\s+to the bottom of your library\.?$",
		RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
	private BridgeProcessClient? bridge;
	private Label statusLabel = null!;
	private Button opponentHeader = null!;
	private FlowContainer opponentNonlands = null!;
	private FlowContainer opponentLands = null!;
	private FlowContainer opponentGraveyard = null!;
	private Label stackTurnText = null!;
	private Label stackPhaseText = null!;
	private Label stackText = null!;
	private Button yourHeader = null!;
	private FlowContainer yourNonlands = null!;
	private FlowContainer yourLands = null!;
	private FlowContainer yourHand = null!;
	private FlowContainer yourGraveyard = null!;
	private Label opponentTurnIndicator = null!;
	private Label opponentPriorityIndicator = null!;
	private Label yourTurnIndicator = null!;
	private Label yourPriorityIndicator = null!;
	private PanelContainer currentActionPanel = null!;
	private Label interactionText = null!;
	private Button passPriorityButton = null!;
	private Button okButton = null!;
	private Button cancelButton = null!;
	private Label actionStatus = null!;
	private PanelContainer abilityPanel = null!;
	private Label abilityTitle = null!;
	private VBoxContainer abilityChoices = null!;
	private TextureRect cardPreview = null!;
	private Label cardPreviewName = null!;
	private CardImageCatalog cardImages = null!;
	private PackedScene cardControlScene = null!;
	private long renderedInteractionSequence;
	private bool stateObserved;
	private bool interactionObserved;
	private string? launchFailure;
	private readonly Dictionary<int, ObservedCardLocation> observedCardLocations = new();
	private readonly Dictionary<int, ObservedStackItem> observedStack = new();
	private readonly Dictionary<int, int> observedPlayerLives = new();
	private bool g3StateInitialized;

	public override void _Ready()
	{
		BindSceneNodes();
		cardImages = new CardImageCatalog("res://card-images/sligh");
		cardControlScene = ResourceLoader.Load<PackedScene>("res://CardControl.tscn");
		passPriorityButton.Pressed += OnPassPriority;
		okButton.Pressed += () => OnButton(BridgeButton.Ok);
		cancelButton.Pressed += () => OnButton(BridgeButton.Cancel);
		opponentHeader.Pressed += () => OnPlayerPressed(opponentHeader);
		yourHeader.Pressed += () => OnPlayerPressed(yourHeader);

		bridge = new BridgeProcessClient();
		string projectDirectory = ProjectSettings.GlobalizePath("res://");
		try
		{
			bridge.Start(new BridgeLaunchOptions(
				JavaExecutable,
				ResolvePath(projectDirectory, BridgeJarPath),
				Host,
				Port,
				Username,
				ResolvePath(projectDirectory, AssetsDirectory),
				Path.GetFullPath(Path.Combine(projectDirectory, ".."))));
		}
		catch (Exception exception)
		{
			launchFailure = exception.Message;
			GD.PushError($"Could not launch forge-bridge: {exception.Message}");
		}
		RenderUi();
	}

	public override void _Process(double delta)
	{
		if (bridge == null)
		{
			return;
		}

		while (bridge.TryDequeueDiagnostic(out string? diagnostic))
		{
			GD.PrintErr($"[forge-bridge] {diagnostic}");
		}

		bool changed = false;
		while (bridge.TryDequeueMessage(out BridgeMessage? message))
		{
			if (message == null)
			{
				continue;
			}
			clientState.Apply(message);
			LogExceptionalMessage(message);
			LogFirstObservation(message);
			LogG3Observation(message);
			changed = true;
		}

		statusLabel.Text = launchFailure == null
			? $"{bridge.Status} | {clientState.ConnectionStatus}"
			: $"Launch failed: {launchFailure}";
		if (changed)
		{
			RenderUi();
		}
	}

	public override void _ExitTree()
	{
		bridge?.Dispose();
		bridge = null;
	}

	private void BindSceneNodes()
	{
		statusLabel = GetNode<Label>("%ConnectionStatus");
		opponentHeader = GetNode<Button>("%OpponentHeader");
		opponentNonlands = GetNode<FlowContainer>("%OpponentNonlands");
		opponentLands = GetNode<FlowContainer>("%OpponentLands");
		opponentGraveyard = GetNode<FlowContainer>("%OpponentGraveyard");
		stackTurnText = GetNode<Label>("%StackTurnText");
		stackPhaseText = GetNode<Label>("%StackPhaseText");
		stackText = GetNode<Label>("%StackText");
		yourHeader = GetNode<Button>("%YourHeader");
		yourNonlands = GetNode<FlowContainer>("%YourNonlands");
		yourLands = GetNode<FlowContainer>("%YourLands");
		yourHand = GetNode<FlowContainer>("%YourHand");
		yourGraveyard = GetNode<FlowContainer>("%YourGraveyard");
		opponentTurnIndicator = GetNode<Label>("%OpponentTurnIndicator");
		opponentPriorityIndicator = GetNode<Label>("%OpponentPriorityIndicator");
		yourTurnIndicator = GetNode<Label>("%YourTurnIndicator");
		yourPriorityIndicator = GetNode<Label>("%YourPriorityIndicator");
		currentActionPanel = GetNode<PanelContainer>("%CurrentActionPanel");
		interactionText = GetNode<Label>("%InteractionText");
		passPriorityButton = GetNode<Button>("%PassPriority");
		okButton = GetNode<Button>("%OkButton");
		cancelButton = GetNode<Button>("%CancelButton");
		actionStatus = GetNode<Label>("%ActionStatus");
		abilityPanel = GetNode<PanelContainer>("%AbilityPanel");
		abilityTitle = GetNode<Label>("%AbilityTitle");
		abilityChoices = GetNode<VBoxContainer>("%AbilityChoices");
		cardPreview = GetNode<TextureRect>("%CardPreview");
		cardPreviewName = GetNode<Label>("%CardPreviewName");
	}

	private void RenderUi()
	{
		StateMessage? state = clientState.LatestState;
		InteractionMessage? interaction = clientState.CurrentInteraction;
		renderedInteractionSequence = interaction?.InteractionSequence ?? 0;
		HashSet<int> selectableCards = interaction == null
			? []
			: interaction.WeaklySelectableCardIds
				.Concat(interaction.SelectableCardIds)
				.ToHashSet();
		HashSet<int> primaryActionCards = interaction == null
			? []
			: selectableCards
				.Concat(interaction.CombatSelectableCardIds)
				.ToHashSet();
		HashSet<int> highlightedCards = interaction?.HighlightedCardIds.ToHashSet() ?? [];

		PlayerSnapshot? localPlayer = FindLocalPlayer(state);
		PlayerSnapshot? opponent = state?.Players.FirstOrDefault(player => player.Id != localPlayer?.Id);
		RenderPlayerArea(yourHeader, "You", localPlayer, interaction, true);
		RenderPlayerArea(opponentHeader, "Opponent", opponent, interaction, false);
		RenderPlayerIndicators(localPlayer, state, yourTurnIndicator, yourPriorityIndicator);
		RenderPlayerIndicators(opponent, state, opponentTurnIndicator, opponentPriorityIndicator);
		RenderTurnAndPhase(state, localPlayer);

		RenderBattlefieldRows(opponentNonlands, opponentLands,
			opponent?.Battlefield ?? [], selectableCards, primaryActionCards,
			highlightedCards, state,
			interaction?.InteractionSequence);
		RenderCardButtons(opponentGraveyard, opponent?.Graveyard ?? [], selectableCards,
			interaction?.InteractionSequence);
		RenderBattlefieldRows(yourNonlands, yourLands,
			localPlayer?.Battlefield ?? [], selectableCards, primaryActionCards,
			highlightedCards, state,
			interaction?.InteractionSequence);
		RenderImageCards(yourHand, localPlayer?.HandVisible ?? [], selectableCards,
			primaryActionCards, highlightedCards, state,
			interaction?.InteractionSequence);
		RenderCardButtons(yourGraveyard, localPlayer?.Graveyard ?? [], selectableCards,
			interaction?.InteractionSequence);
		stackText.Text = $"STACK: {FormatStack(state?.Stack ?? [])}";

		RenderInteraction(interaction, state, localPlayer);
		RenderAbilityQuery(clientState.PendingQuery);
		actionStatus.Text = clientState.LastActionStatus
			?? clientState.LastError
			?? "Choose only controls currently enabled by Forge.";
	}

	private void RenderBattlefieldRows(FlowContainer nonlandRow, FlowContainer landRow,
		IReadOnlyList<CardSnapshot> cards, HashSet<int> selectableIds,
		HashSet<int> primaryActionIds, HashSet<int> highlightedIds, StateMessage? state,
		long? interactionSequence)
	{
		RenderImageCards(nonlandRow, cards.Where(card => !card.IsLand).ToArray(),
			selectableIds, primaryActionIds, highlightedIds, state,
			interactionSequence, renderTappedState: true);
		RenderImageCards(landRow, cards.Where(card => card.IsLand).ToArray(),
			selectableIds, primaryActionIds, highlightedIds, state,
			interactionSequence, renderTappedState: true);
	}

	private void RenderImageCards(Container container, IReadOnlyList<CardSnapshot> cards,
		HashSet<int> selectableIds, HashSet<int> primaryActionIds,
		HashSet<int> highlightedIds, StateMessage? state, long? interactionSequence,
		bool renderTappedState = false)
	{
		ClearChildren(container);
		if (cards.Count == 0)
		{
			container.AddChild(EmptyLabel());
			return;
		}

		foreach (CardSnapshot card in cards)
		{
			bool actionable = interactionSequence != null
				&& selectableIds.Contains(card.Id)
				&& CanSendAsync();
			bool primaryActionEnabled = interactionSequence != null
				&& primaryActionIds.Contains(card.Id)
				&& CanSendAsync();
			bool attacking = state?.Combat.Any(
				assignment => assignment.AttackerCardId == card.Id) == true;
			bool blocking = state?.Combat.Any(
				assignment => assignment.BlockerCardIds.Contains(card.Id)) == true;
			bool selected = highlightedIds.Contains(card.Id);
			string relationshipText = CombatRelationshipText(card.Id, state);
			Texture2D? texture = null;
			if (!card.Hidden && !string.IsNullOrWhiteSpace(card.Name)
				&& cardImages.TryGetTexture(card.Name, out Texture2D resolvedTexture))
			{
				texture = resolvedTexture;
			}

			string displayName = card.Hidden || string.IsNullOrWhiteSpace(card.Name)
				? "Hidden card"
				: card.Name;
			CardControl cardControl = cardControlScene.Instantiate<CardControl>();
			cardControl.Configure(
				displayName,
				texture,
				CardTooltip(card, actionable, primaryActionEnabled, attacking,
					blocking, selected, relationshipText));
			cardControl.SetActionable(actionable);
			cardControl.SetPrimaryActionEnabled(primaryActionEnabled);
			cardControl.SetSelected(selected);
			cardControl.SetAttacking(attacking);
			cardControl.SetBlocking(blocking);
			cardControl.SetRelationshipText(relationshipText);
			if (renderTappedState)
			{
				cardControl.SetTapped(card.Tapped);
			}

			int cardId = card.Id;
			long sequence = interactionSequence ?? 0;
			cardControl.PrimaryActionRequested +=
				() => OnCardPressed(card, cardId, sequence);
			cardControl.PreviewRequested += () => ShowCardPreview(card);
			container.AddChild(cardControl);
		}
	}

	private void RenderCardButtons(Container container, IReadOnlyList<CardSnapshot> cards,
		HashSet<int> selectableIds, long? interactionSequence)
	{
		ClearChildren(container);
		if (cards.Count == 0)
		{
			container.AddChild(EmptyLabel());
			return;
		}

		foreach (CardSnapshot card in cards)
		{
			bool actionable = interactionSequence != null
				&& selectableIds.Contains(card.Id)
				&& CanSendAsync();
			PanelContainer cardControl = new()
			{
				CustomMinimumSize = new Vector2(150, 52),
				MouseFilter = MouseFilterEnum.Stop
			};
			Button button = new()
			{
				Text = actionable
					? $"{CardName(card)}\n#{card.Id}  READY"
					: $"{CardName(card)}\n#{card.Id}",
				TooltipText = actionable
					? $"Forge selectable | {Value(card.Zone)} | id={card.Id}"
					: $"{Value(card.Zone)} | id={card.Id}",
				Disabled = !actionable,
				MouseFilter = MouseFilterEnum.Pass,
				Modulate = actionable ? new Color(0.72f, 1f, 0.78f) : new Color(0.68f, 0.7f, 0.73f)
			};
			button.AddThemeColorOverride("font_color", new Color(0.08f, 0.13f, 0.1f));
			button.AddThemeColorOverride("font_hover_color", new Color(0.03f, 0.08f, 0.04f));
			button.AddThemeColorOverride("font_pressed_color", new Color(0.03f, 0.08f, 0.04f));
			int cardId = card.Id;
			long sequence = interactionSequence ?? 0;
			button.Pressed += () => OnCardPressed(card, cardId, sequence);
			cardControl.GuiInput += inputEvent => OnCardGuiInput(cardControl, card, inputEvent);
			cardControl.AddChild(button);
			container.AddChild(cardControl);
		}
	}

	private void OnCardGuiInput(Control cardControl, CardSnapshot card, InputEvent inputEvent)
	{
		if (inputEvent is not InputEventMouseButton mouseButton
			|| mouseButton.ButtonIndex != MouseButton.Right
			|| !mouseButton.Pressed)
		{
			return;
		}

		cardControl.AcceptEvent();
		ShowCardPreview(card);
	}

	private void ShowCardPreview(CardSnapshot card)
	{
		if (card.Hidden || string.IsNullOrWhiteSpace(card.Name))
		{
			return;
		}

		cardPreviewName.Text = card.Name;
		if (cardImages.TryGetTexture(card.Name, out Texture2D texture))
		{
			cardPreview.Texture = texture;
			return;
		}

		cardPreview.Texture = null;
		cardPreviewName.Text = $"{card.Name}\nPreview image unavailable";
	}

	private void OnCardPressed(CardSnapshot card, int cardId, long interactionSequence)
	{
		GD.Print($"G3 CLICK cardId={cardId} name=\"{Value(card.Name)}\" zone={Value(card.Zone)} "
			+ $"interactionSequence={interactionSequence} prompt=\"{CurrentPrompt()}\"");
		SendAsyncCommand(new SelectCardCommand(cardId, interactionSequence));
	}

	private void RenderPlayerArea(Button area, string role, PlayerSnapshot? player,
		InteractionMessage? interaction, bool isLocal)
	{
		bool actionable = player != null
			&& interaction != null
			&& interaction.SelectablePlayerIds.Contains(player.Id)
			&& CanSendAsync();
		area.Text = player == null
			? $"{role}: waiting for state"
			: $"{role.ToUpperInvariant()}    Life: {player.Life}    Hand: {DisplayedHandCount(player, isLocal)}"
				+ (actionable ? "    READY" : "");
		area.Disabled = !actionable;
		area.Modulate = actionable ? new Color(0.72f, 1f, 0.78f) : new Color(0.68f, 0.7f, 0.73f);
		area.TooltipText = player == null
			? string.Empty
			: $"Forge player: {Value(player.Name)} | id={player.Id}"
				+ (actionable ? " | selectable" : string.Empty);
		area.SetMeta("player_id", player?.Id ?? -1);
	}

	private static string DisplayedHandCount(PlayerSnapshot player, bool isLocal)
	{
		return player.HandCount?.ToString()
			?? (isLocal ? player.HandVisible.Count.ToString() : "?");
	}

	private void RenderTurnAndPhase(StateMessage? state, PlayerSnapshot? localPlayer)
	{
		if (state == null)
		{
			stackTurnText.Text = "TURN —";
			stackPhaseText.Text = "PHASE: —";
			return;
		}

		string activePlayer = localPlayer == null || state.ActivePlayerId == null
			? "ACTIVE PLAYER —"
			: state.ActivePlayerId == localPlayer.Id
				? "YOUR TURN"
				: "OPPONENT'S TURN";
		stackTurnText.Text = $"TURN {state.Turn} — {activePlayer}";
		stackPhaseText.Text = $"PHASE: {FormatPhase(state.Phase)}";
	}

	private static void RenderPlayerIndicators(PlayerSnapshot? player, StateMessage? state,
		Label turnIndicator, Label priorityIndicator)
	{
		turnIndicator.Visible = player != null && state?.ActivePlayerId == player.Id;
		priorityIndicator.Visible = player != null && state?.PriorityPlayerId == player.Id;
	}

	private void OnPlayerPressed(Button area)
	{
		InteractionMessage? interaction = clientState.CurrentInteraction;
		int playerId = area.GetMeta("player_id").AsInt32();
		if (interaction == null || !interaction.SelectablePlayerIds.Contains(playerId))
		{
			actionStatus.Text = "That player is no longer offered by Forge. Choose again.";
			return;
		}
		GD.Print($"G3 CLICK playerId={playerId} interactionSequence={interaction.InteractionSequence} "
			+ $"prompt=\"{CurrentPrompt()}\"");
		SendAsyncCommand(new SelectPlayerCommand(playerId, interaction.InteractionSequence));
	}

	private void RenderInteraction(InteractionMessage? interaction, StateMessage? state,
		PlayerSnapshot? localPlayer)
	{
		if (interaction == null)
		{
			currentActionPanel.Visible = false;
			interactionText.Text = string.Empty;
			passPriorityButton.Disabled = true;
			okButton.Disabled = true;
			cancelButton.Disabled = true;
			return;
		}

		string? currentAction = PlayerFacingPrompt(interaction, state, localPlayer);
		currentActionPanel.Visible = currentAction != null;
		interactionText.Text = currentAction ?? string.Empty;
		bool canSend = CanSendAsync();
		okButton.Text = Value(interaction.Buttons.OkLabel);
		cancelButton.Text = Value(interaction.Buttons.CancelLabel);
		okButton.Disabled = !canSend || !interaction.Buttons.OkEnabled;
		cancelButton.Disabled = !canSend || !interaction.Buttons.CancelEnabled;
		passPriorityButton.Disabled = !canSend || localPlayer == null
			|| state?.PriorityPlayerId != localPlayer.Id
			|| !string.Equals(interaction.Reason, "buttons", StringComparison.Ordinal);
	}

	private static string? PlayerFacingPrompt(InteractionMessage interaction, StateMessage? state,
		PlayerSnapshot? localPlayer)
	{
		if (IsDeclareBlockers(state, interaction))
		{
			return BlockerDeclarationPrompt(interaction, state!);
		}

		if (string.IsNullOrWhiteSpace(interaction.Prompt))
		{
			return null;
		}

		string trimmed = interaction.Prompt.Trim();
		if (IsRoutinePriorityStatus(trimmed))
		{
			return null;
		}

		Match mulligan = LondonMulliganPrompt.Match(trimmed);
		if (mulligan.Success
			&& int.TryParse(mulligan.Groups["remaining"].Value, out int remaining))
		{
			if (remaining > 0)
			{
				return $"Select {remaining} {(remaining == 1 ? "card" : "cards")} "
					+ "to put on the bottom of your library.";
			}

			string selectedCards = interaction.Max == 1 ? "card" : "cards";
			return $"Send the selected {selectedCards} to the bottom of your library?";
		}

		if (state != null)
		{
			foreach (PlayerSnapshot player in state.Players)
			{
				if (!string.IsNullOrWhiteSpace(player.Name))
				{
					string friendlyName = player.Id == localPlayer?.Id ? "You" : "Opponent";
					trimmed = trimmed.Replace(player.Name, friendlyName,
						StringComparison.Ordinal);
				}
			}
		}

		return trimmed;
	}

	private static bool IsDeclareBlockers(StateMessage? state, InteractionMessage interaction)
	{
		return string.Equals(state?.Phase, "COMBAT_DECLARE_BLOCKERS",
			StringComparison.OrdinalIgnoreCase)
			&& (state!.Combat.Count > 0 || interaction.CombatSelectableCardIds.Count > 0);
	}

	private static string BlockerDeclarationPrompt(InteractionMessage interaction,
		StateMessage state)
	{
		HashSet<int> attackerIds = state.Combat
			.Select(assignment => assignment.AttackerCardId)
			.ToHashSet();
		int? selectedAttackerId = interaction.HighlightedCardIds
			.FirstOrDefault(attackerIds.Contains);
		if (selectedAttackerId == 0 && !attackerIds.Contains(0))
		{
			selectedAttackerId = null;
		}

		List<string> lines = ["DECLARE BLOCKERS", string.Empty];
		if (selectedAttackerId.HasValue)
		{
			string attackerName = CardNameById(state, selectedAttackerId.Value);
			lines.Add($"Current attacker: {attackerName}");
			lines.Add(interaction.WeaklySelectableCardIds.Count > 0
				? "Select a green creature to block it. Click it again to remove that assignment."
				: "Forge offers no blocker that can be toggled for this attacker.");
		}
		else
		{
			lines.Add("Select an orange attacking creature to choose its blockers.");
		}

		lines.Add(string.Empty);
		lines.Add("ASSIGNMENTS");
		List<string> assignments = state.Combat
			.SelectMany(combat => combat.BlockerCardIds.Select(blockerId =>
				$"{CardNameById(state, blockerId)} → "
				+ CardNameById(state, combat.AttackerCardId)))
			.ToList();
		lines.AddRange(assignments.Count == 0
			? ["None — leaving this empty declares no blockers if Forge permits it."]
			: assignments);

		lines.Add(string.Empty);
		string confirmLabel = Value(interaction.Buttons.OkLabel);
		lines.Add($"Select another attacker to review or change its blockers. "
			+ $"Press {confirmLabel} when finished; Forge validates the declaration.");
		return string.Join("\n", lines);
	}

	private static bool IsRoutinePriorityStatus(string prompt)
	{
		return prompt.StartsWith("Priority:", StringComparison.OrdinalIgnoreCase)
			&& prompt.Contains(" Turn:", StringComparison.OrdinalIgnoreCase)
			&& prompt.Contains(" Phase:", StringComparison.OrdinalIgnoreCase)
			&& prompt.Contains(" Stack:", StringComparison.OrdinalIgnoreCase);
	}

	private void RenderAbilityQuery(QueryMessage? query)
	{
		ClearChildren(abilityChoices);
		abilityPanel.Visible = query != null;
		if (query == null)
		{
			return;
		}
		if (query.Kind == "combatDamageAssignment"
			&& query.TotalDamage.HasValue
			&& query.Recipients.Count > 0
			&& query.Constraints != null)
		{
			RenderCombatDamageQuery(query);
			return;
		}
		if (query.Kind == "itemOrdering" && query.Items.Count > 0)
		{
			RenderItemOrderingQuery(query);
			return;
		}

		abilityTitle.Text = $"Forge requires a choice: {Value(query.Kind)}\n"
			+ $"{Value(query.HostCardName)}  requestId={Value(query.RequestId)}";
		if (query.Choices.Count == 0)
		{
			Label unsupported = EmptyLabel("No reply choices were supplied; G3 will not invent a response.");
			unsupported.AutowrapMode = TextServer.AutowrapMode.WordSmart;
			abilityChoices.AddChild(unsupported);
			return;
		}

		foreach (QueryChoice choice in query.Choices)
		{
			Button button = new()
			{
				Text = $"{Value(choice.Description)}  [#{choice.Id}]",
				Disabled = !choice.CanPlay,
				TooltipText = choice.CanPlay ? "Reply with this Forge-offered choice" : "Forge reports this choice is unavailable"
			};
			string requestId = query.RequestId ?? string.Empty;
			int selectedId = choice.Id;
			button.Pressed += () => SendReply(requestId, selectedId);
			abilityChoices.AddChild(button);
		}
	}

	private void RenderItemOrderingQuery(QueryMessage query)
	{
		string requestId = query.RequestId ?? string.Empty;
		abilityTitle.Text = $"{Value(query.Title).ToUpperInvariant()}\n{Value(query.Prompt)}";

		Dictionary<string, OrderingItem> itemsById = query.Items
			.Where(item => !string.IsNullOrWhiteSpace(item.ItemId))
			.GroupBy(item => item.ItemId!)
			.ToDictionary(group => group.Key, group => group.First());
		List<OrderingItem> ordered = query.OriginalOrder
			.Where(itemsById.ContainsKey)
			.Distinct(StringComparer.Ordinal)
			.Select(itemId => itemsById[itemId])
			.ToList();
		if (ordered.Count != query.Items.Count || itemsById.Count != query.Items.Count)
		{
			ordered = query.Items.OrderBy(item => item.OriginalPosition).ToList();
		}

		Label guidance = new()
		{
			Text = query.Mandatory == true
				? "Select a row, move it up or down, then confirm the complete order."
				: "Select and arrange the Forge-offered items, then confirm.",
			AutowrapMode = TextServer.AutowrapMode.WordSmart
		};
		abilityChoices.AddChild(guidance);

		VBoxContainer rows = new();
		abilityChoices.AddChild(rows);
		int selectedIndex = ordered.Count > 0 ? 0 : -1;

		HBoxContainer actions = new();
		Button moveUp = new() { Text = "Move Up" };
		Button moveDown = new() { Text = "Move Down" };
		Button confirm = new() { Text = "Confirm Order" };
		actions.AddChild(moveUp);
		actions.AddChild(moveDown);
		actions.AddChild(confirm);
		abilityChoices.AddChild(actions);

		CheckButton? remember = null;
		if (query.RememberAllowed == true)
		{
			remember = new CheckButton { Text = "Remember this order for identical future triggers" };
			abilityChoices.AddChild(remember);
		}

		void RefreshRows()
		{
			ClearChildren(rows);
			for (int index = 0; index < ordered.Count; index++)
			{
				OrderingItem item = ordered[index];
				string source = item.SourceCardId.HasValue
					? $" — {Value(item.SourceCardName)} [card #{item.SourceCardId.Value}]"
					: string.Empty;
				Button row = new()
				{
					Text = $"{index + 1}. {Value(item.Description)}{source}  [{Value(item.ItemId)}]",
					Alignment = HorizontalAlignment.Left,
					TooltipText = "Opaque item IDs keep identical triggers separate."
				};
				int clickedIndex = index;
				row.Pressed += () =>
				{
					selectedIndex = clickedIndex;
					RefreshRows();
				};
				if (index == selectedIndex)
				{
					row.Text = $"▶ {row.Text}";
				}
				rows.AddChild(row);
			}
			moveUp.Disabled = selectedIndex <= 0;
			moveDown.Disabled = selectedIndex < 0 || selectedIndex >= ordered.Count - 1;
			confirm.Disabled = ordered.Count == 0
				|| ordered.Any(item => string.IsNullOrWhiteSpace(item.ItemId));
		}

		moveUp.Pressed += () =>
		{
			if (selectedIndex <= 0)
			{
				return;
			}
			(ordered[selectedIndex - 1], ordered[selectedIndex]) =
				(ordered[selectedIndex], ordered[selectedIndex - 1]);
			selectedIndex--;
			RefreshRows();
		};
		moveDown.Pressed += () =>
		{
			if (selectedIndex < 0 || selectedIndex >= ordered.Count - 1)
			{
				return;
			}
			(ordered[selectedIndex + 1], ordered[selectedIndex]) =
				(ordered[selectedIndex], ordered[selectedIndex + 1]);
			selectedIndex++;
			RefreshRows();
		};
		confirm.Pressed += () => SendItemOrderingReply(requestId,
			ordered.Select(item => item.ItemId ?? string.Empty).ToArray(),
			remember?.ButtonPressed == true);
		RefreshRows();
	}

	private void RenderCombatDamageQuery(QueryMessage query)
	{
		int totalDamage = query.TotalDamage!.Value;
		CombatDamageConstraints constraints = query.Constraints!;
		string requestId = query.RequestId ?? string.Empty;
		abilityTitle.Text = $"ASSIGN COMBAT DAMAGE — {Value(query.HostCardName)} ({totalDamage})";

		List<(CombatDamageRecipient Recipient, SpinBox Amount)> editors = [];
		foreach (CombatDamageRecipient recipient in query.Recipients.OrderBy(item => item.Order))
		{
			HBoxContainer row = new()
			{
				SizeFlagsHorizontal = Control.SizeFlags.ExpandFill
			};
			string role = recipient.Role == "defender" ? "Defender" : "Blocker";
			string minimum = recipient.Role == "blocker"
				? $" — lethal {recipient.MinimumDamage}"
				: string.Empty;
			Label name = new()
			{
				Text = $"{role}: {Value(recipient.Name)}{minimum}",
				SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
				VerticalAlignment = VerticalAlignment.Center
			};
			SpinBox amount = new()
			{
				MinValue = 0,
				MaxValue = totalDamage,
				Step = 1,
				Value = 0,
				AllowGreater = false,
				AllowLesser = false,
				CustomMinimumSize = new Vector2(100, 0)
			};
			row.AddChild(name);
			row.AddChild(amount);
			abilityChoices.AddChild(row);
			editors.Add((recipient, amount));
		}

		// Start from a legal, deliberately non-opinionated assignment. The player can
		// reduce this amount and distribute the remainder among every offered recipient.
		editors[0].Amount.Value = totalDamage;
		Label assignmentStatus = new()
		{
			AutowrapMode = TextServer.AutowrapMode.WordSmart
		};
		abilityChoices.AddChild(assignmentStatus);

		HBoxContainer actions = new();
		Button confirm = new() { Text = "Confirm damage assignment" };
		actions.AddChild(confirm);
		if (constraints.MaySkip)
		{
			Button skip = new() { Text = "Assign another attacker first" };
			skip.Pressed += () => SendCombatDamageReply(
				requestId, Array.Empty<CombatDamageAmount>(), skip: true);
			actions.AddChild(skip);
		}
		abilityChoices.AddChild(actions);

		void RefreshAssignment()
		{
			Dictionary<string, int> amounts = editors.ToDictionary(
				entry => entry.Recipient.Key ?? string.Empty,
				entry => Convert.ToInt32(entry.Amount.Value));
			int assigned = amounts.Values.Sum();
			string? problem = ValidateCombatDamageQuery(query, amounts);
			int remaining = totalDamage - assigned;
			assignmentStatus.Text = problem == null
				? "Remaining: 0 — assignment is legal"
				: $"Remaining: {remaining} — {problem}";
			confirm.Disabled = problem != null;
		}

		foreach ((CombatDamageRecipient _, SpinBox amount) in editors)
		{
			amount.ValueChanged += _ => RefreshAssignment();
		}
		confirm.Pressed += () => SendCombatDamageReply(requestId,
			editors.Select(entry => new CombatDamageAmount(
				entry.Recipient.Key ?? string.Empty,
				Convert.ToInt32(entry.Amount.Value))).ToArray(), skip: false);
		RefreshAssignment();
	}

	private static string? ValidateCombatDamageQuery(QueryMessage query,
		IReadOnlyDictionary<string, int> amounts)
	{
		int totalDamage = query.TotalDamage ?? 0;
		if (amounts.Values.Any(amount => amount < 0))
		{
			return "Damage cannot be negative.";
		}
		if (amounts.Values.Sum() != totalDamage)
		{
			return $"Assign exactly {totalDamage} damage.";
		}

		CombatDamageConstraints constraints = query.Constraints!;
		if (constraints.FreeAssignment)
		{
			return null;
		}

		bool allEarlierBlockersHaveLethal = true;
		foreach (CombatDamageRecipient recipient in query.Recipients.OrderBy(item => item.Order))
		{
			int amount = amounts.GetValueOrDefault(recipient.Key ?? string.Empty);
			if (recipient.Role == "defender")
			{
				if (constraints.DefenderRequiresLethalBlockers
					&& amount > 0 && !allEarlierBlockersHaveLethal)
				{
					return "Assign lethal damage to every blocker before the defender.";
				}
				continue;
			}

			if (constraints.OrderedAssignment && amount > 0
				&& !allEarlierBlockersHaveLethal)
			{
				return "Assign lethal damage to each earlier blocker first.";
			}
			allEarlierBlockersHaveLethal &= amount >= recipient.MinimumDamage;
		}
		return null;
	}

	private void SendCombatDamageReply(string requestId,
		IReadOnlyList<CombatDamageAmount> assignments, bool skip)
	{
		QueryMessage? query = clientState.PendingQuery;
		if (query == null || query.RequestId != requestId
			|| query.Kind != "combatDamageAssignment")
		{
			actionStatus.Text = "That damage assignment is no longer pending.";
			return;
		}
		GD.Print($"G3 COMBAT_DAMAGE_REPLY requestId={requestId} "
			+ $"skip={skip} assignments={string.Join(",", assignments.Select(
				assignment => $"{assignment.RecipientKey}:{assignment.Amount}"))}");
		SendCommand(new CombatDamageReplyCommand(requestId, assignments, skip));
	}

	private void SendItemOrderingReply(string requestId,
		IReadOnlyList<string> orderedItemIds, bool rememberDecision)
	{
		QueryMessage? query = clientState.PendingQuery;
		if (query == null || query.RequestId != requestId || query.Kind != "itemOrdering")
		{
			actionStatus.Text = "That ordering query is no longer pending.";
			return;
		}
		HashSet<string> offered = query.Items
			.Select(item => item.ItemId ?? string.Empty)
			.ToHashSet(StringComparer.Ordinal);
		if (orderedItemIds.Count != offered.Count
			|| orderedItemIds.Distinct(StringComparer.Ordinal).Count() != offered.Count
			|| orderedItemIds.Any(itemId => !offered.Contains(itemId)))
		{
			actionStatus.Text = "The order must contain every offered item exactly once.";
			return;
		}
		GD.Print($"G3 ITEM_ORDER_REPLY requestId={requestId} "
			+ $"order={string.Join(",", orderedItemIds)} remember={rememberDecision}");
		SendCommand(new ItemOrderingReplyCommand(requestId, orderedItemIds, rememberDecision));
	}

	private void OnPassPriority()
	{
		if (renderedInteractionSequence > 0)
		{
			GD.Print($"G3 PASS_PRIORITY interactionSequence={renderedInteractionSequence} "
				+ $"prompt=\"{CurrentPrompt()}\"");
			SendAsyncCommand(new PassPriorityCommand(renderedInteractionSequence));
		}
	}

	private void OnButton(BridgeButton button)
	{
		if (renderedInteractionSequence > 0)
		{
			SendAsyncCommand(new ButtonCommand(button, renderedInteractionSequence));
		}
	}

	private void SendAsyncCommand(AsyncInteractionCommand command)
	{
		if (command.InteractionSequence != renderedInteractionSequence)
		{
			actionStatus.Text = "Interaction changed before the click was sent. Choose again.";
			return;
		}
		SendCommand(command);
	}

	private void SendReply(string requestId, int selectedId)
	{
		QueryMessage? query = clientState.PendingQuery;
		if (query == null || query.RequestId != requestId
			|| query.Choices.All(choice => choice.Id != selectedId))
		{
			actionStatus.Text = "That query is no longer pending. Choose from the current query.";
			return;
		}
		QueryChoice? choice = query.Choices.FirstOrDefault(candidate => candidate.Id == selectedId);
		GD.Print($"G3 QUERY_REPLY requestId={requestId} selectedId={selectedId} "
			+ $"description=\"{Value(choice?.Description)}\"");
		SendCommand(new ReplyCommand(requestId, selectedId));
	}

	private void SendCommand(BridgeCommand command)
	{
		string? error = null;
		if (bridge == null || !bridge.TrySend(command, out error))
		{
			actionStatus.Text = error ?? "Could not send command to forge-bridge.";
			return;
		}
		clientState.RecordCommandSent(command);
		GD.Print($"G2_COMMAND_SENT type={command.Type}");
		RenderUi();
	}

	private bool CanSendAsync()
	{
		return bridge?.IsRunning == true
			&& clientState.PendingAsyncCommand == null
			&& clientState.PendingQuery == null;
	}

	private string CurrentPrompt()
	{
		return Value(clientState.CurrentInteraction?.Prompt).Replace('"', '\'');
	}

	private PlayerSnapshot? FindLocalPlayer(StateMessage? state)
	{
		int? playerId = clientState.Controller?.PlayerId;
		return state == null || playerId == null
			? null
			: state.Players.FirstOrDefault(player => player.Id == playerId.Value);
	}

	private static string FormatPhase(string? phase)
	{
		if (string.IsNullOrWhiteSpace(phase))
		{
			return "—";
		}

		string normalized = phase.ToUpperInvariant();
		return normalized switch
		{
			"MAIN1" => "Main phase — precombat",
			"MAIN2" => "Main phase — postcombat",
			"END_OF_TURN" => "End step",
			_ when normalized.StartsWith("COMBAT_", StringComparison.Ordinal) =>
				$"Combat — {TitleWords(normalized[7..])}",
			_ => TitleWords(normalized)
		};
	}

	private static string TitleWords(string value)
	{
		return string.Join(" ", value.Split('_', StringSplitOptions.RemoveEmptyEntries)
			.Select(word => char.ToUpperInvariant(word[0]) + word[1..].ToLowerInvariant()));
	}

	private static string FormatStack(IReadOnlyList<StackSnapshot> stack)
	{
		if (stack.Count == 0)
		{
			return "Empty";
		}

		return string.Join("  •  ", stack.Select(item =>
		{
			string source = item.Source == null
				? Value(item.Text)
				: CardName(item.Source);
			return item.Targets.Count == 0
				? source
				: $"{source} → {string.Join(", ", item.Targets.Select(CardName))}";
		}));
	}

	private static string CombatRelationshipText(int cardId, StateMessage? state)
	{
		if (state == null)
		{
			return string.Empty;
		}

		List<string> relationships = [];
		CombatSnapshot? attacking = state.Combat.FirstOrDefault(
			assignment => assignment.AttackerCardId == cardId);
		if (attacking != null && attacking.BlockerCardIds.Count > 0)
		{
			relationships.Add("BLOCKED BY:\n" + string.Join(", ",
				attacking.BlockerCardIds.Select(id => CardNameById(state, id))));
		}

		List<int> blockedAttackers = state.Combat
			.Where(assignment => assignment.BlockerCardIds.Contains(cardId))
			.Select(assignment => assignment.AttackerCardId)
			.ToList();
		if (blockedAttackers.Count > 0)
		{
			relationships.Add("BLOCKING:\n" + string.Join(", ",
				blockedAttackers.Select(id => CardNameById(state, id))));
		}
		return string.Join("\n", relationships);
	}

	private static string CardTooltip(CardSnapshot card, bool actionable,
		bool primaryActionEnabled, bool attacking, bool blocking, bool selected,
		string relationshipText)
	{
		List<string> states = [];
		if (actionable) states.Add("Forge selectable");
		else if (primaryActionEnabled) states.Add("Forge combat selectable");
		if (selected) states.Add("selected");
		if (attacking) states.Add("attacking");
		if (blocking) states.Add("blocking");
		if (!string.IsNullOrWhiteSpace(relationshipText))
		{
			states.Add(relationshipText.Replace('\n', ' '));
		}
		states.Add(Value(card.Zone));
		states.Add($"id={card.Id}");
		return string.Join(" | ", states);
	}

	private static string CardNameById(StateMessage state, int cardId)
	{
		CardSnapshot? card = state.Players
			.SelectMany(player => player.HandVisible
				.Concat(player.Battlefield)
				.Concat(player.Graveyard))
			.FirstOrDefault(candidate => candidate.Id == cardId);
		return card == null ? $"Card #{cardId}" : CardName(card);
	}

	private static void ClearChildren(Node parent)
	{
		foreach (Node child in parent.GetChildren())
		{
			child.QueueFree();
		}
	}

	private static Label EmptyLabel(string text = "(empty)")
	{
		return new Label { Text = text, Modulate = new Color(0.62f, 0.65f, 0.68f) };
	}

	private static string CardName(CardSnapshot card)
	{
		return card.Hidden || string.IsNullOrWhiteSpace(card.Name) ? "<hidden>" : card.Name;
	}

	private static string Value(string? value)
	{
		return string.IsNullOrWhiteSpace(value) ? "-" : value;
	}

	private static string ResolvePath(string projectDirectory, string configuredPath)
	{
		return Path.GetFullPath(Path.IsPathRooted(configuredPath)
			? configuredPath
			: Path.Combine(projectDirectory, configuredPath));
	}

	private static void LogExceptionalMessage(BridgeMessage message)
	{
		switch (message)
		{
			case QueryMessage query:
				GD.PushWarning($"Bridge query {query.RequestId} ({query.Kind}) requires a human choice.");
				break;
			case ErrorMessage error:
				GD.PushError($"Bridge error {error.Code}: {error.Message}");
				break;
			case ProtocolProblemMessage problem:
				GD.PushError($"Bridge protocol problem {problem.Code}: {problem.Detail}");
				break;
			case UnknownBridgeMessage unknown:
				GD.PushWarning($"Unknown bridge message type '{unknown.UnknownType}'.");
				break;
		}
	}

	private void LogG3Observation(BridgeMessage message)
	{
		if (message is InteractionMessage interaction)
		{
			GD.Print($"G3 SELECTABLES callback={Value(interaction.Reason)} "
				+ $"interactionSequence={interaction.InteractionSequence} "
				+ $"selectableCardIds=[{string.Join(",", interaction.SelectableCardIds)}] "
				+ $"selectablePlayerIds=[{string.Join(",", interaction.SelectablePlayerIds)}]");
		}
		else if (message is QueryMessage query)
		{
			string choices = string.Join(" | ", query.Choices.Select(choice =>
				$"{choice.Id}:{Value(choice.Description)} canPlay={choice.CanPlay}"));
			GD.Print($"G3 QUERY kind={Value(query.Kind)} requestId={Value(query.RequestId)} "
				+ $"hostCardId={query.HostCardId?.ToString() ?? "-"} "
				+ $"hostCard=\"{Value(query.HostCardName)}\" choices=[{choices}]");
		}
		else if (message is StateMessage state)
		{
			LogG3StateTransitions(state);
		}
	}

	private void LogG3StateTransitions(StateMessage state)
	{
		Dictionary<int, ObservedCardLocation> currentCards = new();
		foreach (PlayerSnapshot player in state.Players)
		{
			if (observedPlayerLives.TryGetValue(player.Id, out int previousLife)
				&& previousLife != player.Life)
			{
				GD.Print($"G3 LIFE playerId={player.Id} name=\"{Value(player.Name)}\" "
					+ $"from={previousLife} to={player.Life} delta={player.Life - previousLife} "
					+ $"stateSequence={state.StateSequence}");
			}
			observedPlayerLives[player.Id] = player.Life;
			IndexCards(currentCards, player, player.HandVisible, "Hand");
			IndexCards(currentCards, player, player.Battlefield, "Battlefield");
			IndexCards(currentCards, player, player.Graveyard, "Graveyard");
		}

		Dictionary<int, ObservedStackItem> currentStack = state.Stack.ToDictionary(
			item => item.Id,
			item => new ObservedStackItem(
				item.Id,
				item.Source?.Id,
				item.Source == null ? Value(item.Text) : CardName(item.Source),
				item.Targets.Select(target => new ObservedTarget(target.Id, CardName(target))).ToArray()));

		if (g3StateInitialized)
		{
			foreach ((int id, ObservedCardLocation current) in currentCards)
			{
				if (observedCardLocations.TryGetValue(id, out ObservedCardLocation? previous)
					&& previous.Zone != current.Zone)
				{
					GD.Print($"G3 ZONE cardId={id} name=\"{current.Name}\" owner=\"{current.Owner}\" "
						+ $"from={previous.Zone} to={current.Zone} stateSequence={state.StateSequence}");
				}
			}

			foreach ((int id, ObservedStackItem stackItem) in currentStack)
			{
				if (!observedStack.ContainsKey(id))
				{
					GD.Print($"G3 STACK observed stackId={id} sourceId={stackItem.SourceId?.ToString() ?? "-"} "
						+ $"source=\"{stackItem.SourceName}\" targets=[{FormatTargets(stackItem.Targets)}] "
						+ $"stateSequence={state.StateSequence}");
				}
			}

			foreach ((int id, ObservedStackItem previous) in observedStack)
			{
				if (currentStack.ContainsKey(id))
				{
					continue;
				}
				string targetZones = string.Join(", ", previous.Targets.Select(target =>
				{
					string zone = currentCards.TryGetValue(target.Id, out ObservedCardLocation? location)
						? location.Zone
						: "notInProjectedZones";
					return $"{target.Id}:{target.Name} zone={zone}";
				}));
				GD.Print($"G3 RESOLUTION stackId={id} source=\"{previous.SourceName}\" "
					+ $"stackPresent=false targets=[{targetZones}] stateSequence={state.StateSequence}");
			}
		}

		foreach ((int id, ObservedCardLocation location) in currentCards)
		{
			observedCardLocations[id] = location;
		}
		observedStack.Clear();
		foreach ((int id, ObservedStackItem item) in currentStack)
		{
			observedStack[id] = item;
		}
		g3StateInitialized = true;
	}

	private static void IndexCards(Dictionary<int, ObservedCardLocation> destination,
		PlayerSnapshot player, IEnumerable<CardSnapshot> cards, string zone)
	{
		foreach (CardSnapshot card in cards)
		{
			destination[card.Id] = new ObservedCardLocation(
				card.Id, CardName(card), Value(player.Name), zone);
		}
	}

	private static string FormatTargets(IEnumerable<ObservedTarget> targets)
	{
		return string.Join(", ", targets.Select(target => $"{target.Id}:{target.Name}"));
	}

	private void LogFirstObservation(BridgeMessage message)
	{
		if (message is StateMessage state && !stateObserved)
		{
			stateObserved = true;
			GD.Print($"G1_STATE_OBSERVED sequence={state.StateSequence} turn={state.Turn} phase={Value(state.Phase)}");
		}
		else if (message is InteractionMessage interaction && !interactionObserved)
		{
			interactionObserved = true;
			GD.Print($"G1_INTERACTION_OBSERVED sequence={interaction.InteractionSequence} reason={Value(interaction.Reason)}");
		}
	}

	private sealed record ObservedCardLocation(int Id, string Name, string Owner, string Zone);
	private sealed record ObservedTarget(int Id, string Name);
	private sealed record ObservedStackItem(
		int Id, int? SourceId, string SourceName, IReadOnlyList<ObservedTarget> Targets);
}
