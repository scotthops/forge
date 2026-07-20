#nullable enable

using Godot;
using PremodernClient.Bridge;
using PremodernClient.Protocol;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

public partial class Main : Control
{
	[ExportGroup("Forge Bridge")]
	[Export] public string JavaExecutable { get; set; } = "java";
	[Export(PropertyHint.File, "*.jar")]
	public string BridgeJarPath { get; set; }
		= "../forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar";
	[Export(PropertyHint.Dir)] public string AssetsDirectory { get; set; } = "../forge-gui";
	[Export] public string Host { get; set; } = "localhost";
	[Export(PropertyHint.Range, "1,65535,1")]
	public int Port { get; set; } = 36743;
	[Export] public string Username { get; set; } = "Godot Bridge Client";

	private readonly BridgeClientState clientState = new();
	private BridgeProcessClient? bridge;
	private Label statusLabel = null!;
	private RichTextLabel stateText = null!;
	private bool stateObserved;
	private bool interactionObserved;
	private string? launchFailure;

	public override void _Ready()
	{
		statusLabel = GetNode<Label>("%ConnectionStatus");
		stateText = GetNode<RichTextLabel>("%StateText");
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
			statusLabel.Text = $"Launch failed: {exception.Message}";
		}

		RenderState();
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
			changed = true;
		}

		statusLabel.Text = launchFailure == null
			? $"{bridge.Status} | {clientState.ConnectionStatus}"
			: $"Launch failed: {launchFailure}";
		if (changed)
		{
			RenderState();
		}
	}

	public override void _ExitTree()
	{
		bridge?.Dispose();
		bridge = null;
	}

	private void RenderState()
	{
		StringBuilder text = new();
		StateMessage? state = clientState.LatestState;
		if (state == null)
		{
			text.AppendLine("Waiting for authoritative game state...");
		}
		else
		{
			text.AppendLine($"STATE  sequence={state.StateSequence} source={Value(state.Source)}");
			text.AppendLine($"Turn: {state.Turn}");
			text.AppendLine($"Phase: {Value(state.Phase)}");
			text.AppendLine($"Active player: {PlayerName(state, state.ActivePlayerId)}");
			text.AppendLine($"Priority player: {PlayerName(state, state.PriorityPlayerId)}");
			text.AppendLine();

			foreach (PlayerSnapshot player in state.Players)
			{
				text.AppendLine($"PLAYER {Value(player.Name)}  id={player.Id}  life={player.Life}");
				if (player.Id == clientState.Controller?.PlayerId)
				{
					AppendCards(text, "  Visible hand", player.HandVisible);
				}
				AppendCards(text, "  Battlefield", player.Battlefield);
				AppendCards(text, "  Graveyard", player.Graveyard);
				text.AppendLine();
			}

			text.AppendLine("STACK");
			if (state.Stack.Count == 0)
			{
				text.AppendLine("  (empty)");
			}
			foreach (StackSnapshot item in state.Stack)
			{
				string source = item.Source == null
					? Value(item.Text)
					: $"{CardName(item.Source)} [{item.Source.Id}]";
				text.AppendLine($"  {source} -> targets: {FormatTargets(item.Targets)}");
			}
		}

		text.AppendLine();
		AppendInteraction(text, clientState.CurrentInteraction);
		if (clientState.PendingQuery != null)
		{
			QueryMessage query = clientState.PendingQuery;
			text.AppendLine();
			text.AppendLine($"PENDING QUERY (not answered in G1): {Value(query.Kind)} requestId={Value(query.RequestId)}");
		}
		if (!string.IsNullOrWhiteSpace(clientState.LastError))
		{
			text.AppendLine();
			text.AppendLine($"LAST ERROR: {clientState.LastError}");
		}
		if (!string.IsNullOrWhiteSpace(clientState.LastNotice))
		{
			text.AppendLine($"NOTICE: {clientState.LastNotice}");
		}
		stateText.Text = text.ToString();
	}

	private static void AppendCards(StringBuilder text, string label, IReadOnlyList<CardSnapshot> cards)
	{
		text.AppendLine($"{label}:");
		if (cards.Count == 0)
		{
			text.AppendLine("    (empty)");
			return;
		}
		foreach (CardSnapshot card in cards)
		{
			string tapped = card.Tapped ? " tapped" : string.Empty;
			text.AppendLine($"    [{card.Id}] {CardName(card)}{tapped}");
		}
	}

	private static void AppendInteraction(StringBuilder text, InteractionMessage? interaction)
	{
		text.AppendLine("INTERACTION");
		if (interaction == null)
		{
			text.AppendLine("  Waiting for interaction...");
			return;
		}
		text.AppendLine($"  sequence={interaction.InteractionSequence} reason={Value(interaction.Reason)}");
		text.AppendLine($"  prompt={Value(interaction.Prompt)}");
		text.AppendLine($"  weaklySelectable=[{string.Join(", ", interaction.WeaklySelectableCardIds)}]");
		text.AppendLine($"  selectable=[{string.Join(", ", interaction.SelectableCardIds)}]");
		text.AppendLine($"  selectablePlayers=[{string.Join(", ", interaction.SelectablePlayerIds)}]");
		text.AppendLine($"  OK: {Value(interaction.Buttons.OkLabel)} enabled={interaction.Buttons.OkEnabled}");
		text.AppendLine($"  Cancel: {Value(interaction.Buttons.CancelLabel)} enabled={interaction.Buttons.CancelEnabled}");
	}

	private static string PlayerName(StateMessage state, int? id)
	{
		if (id == null)
		{
			return "-";
		}
		PlayerSnapshot? player = state.Players.FirstOrDefault(candidate => candidate.Id == id.Value);
		return player == null ? $"id={id}" : $"{Value(player.Name)} [{id}]";
	}

	private static string FormatTargets(IReadOnlyList<CardSnapshot> targets)
	{
		return targets.Count == 0
			? "[]"
			: $"[{string.Join(", ", targets.Select(target => $"{CardName(target)} [{target.Id}]"))}]";
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
				GD.PushWarning($"Bridge query {query.RequestId} ({query.Kind}) is pending; G1 will not answer it.");
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
}
