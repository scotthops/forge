#nullable enable

using PremodernClient.Protocol;
using System;
using System.Collections.Generic;
using System.Linq;

namespace PremodernClient.Presentation;

public sealed record BattlefieldCardIdentity(
	int CardId,
	string Name,
	string? Badge,
	string DisplayLabel,
	string QualifiedLabel,
	bool IsLocal);

public sealed class BattlefieldCardIdentityMap
{
	private readonly Dictionary<int, Assignment> assignments = new();
	private readonly Dictionary<GroupKey, int> nextOrdinals = new();
	private readonly Dictionary<int, BattlefieldCardIdentity> visibleIdentities = new();

	public void Update(StateMessage? state, int? localPlayerId)
	{
		visibleIdentities.Clear();
		if (state == null || !localPlayerId.HasValue)
		{
			return;
		}

		List<VisibleCard> visibleCards = state.Players
			.SelectMany(player => player.Battlefield
				.Where(card => !card.Hidden && !string.IsNullOrWhiteSpace(card.Name))
				.Select(card => new VisibleCard(player.Id, player.Id == localPlayerId.Value, card)))
			.ToList();
		Dictionary<GroupKey, int> duplicateCounts = visibleCards
			.GroupBy(card => new GroupKey(card.PlayerId, NormalizeName(card.Card.Name!)))
			.ToDictionary(group => group.Key, group => group.Count());

		foreach (VisibleCard visible in visibleCards)
		{
			string name = visible.Card.Name!;
			GroupKey group = new(visible.PlayerId, NormalizeName(name));
			if (!assignments.TryGetValue(visible.Card.Id, out Assignment? assignment)
				|| assignment.Group != group)
			{
				int ordinal = nextOrdinals.GetValueOrDefault(group) + 1;
				nextOrdinals[group] = ordinal;
				assignment = new Assignment(group, ordinal);
				assignments[visible.Card.Id] = assignment;
			}

			bool duplicate = duplicateCounts[group] > 1;
			string? badge = duplicate
				? FormatBadge(assignment.Ordinal, visible.IsLocal)
				: null;
			string displayLabel = badge == null ? name : $"{name} {badge}";
			string side = visible.IsLocal ? "Your" : "Opponent";
			visibleIdentities[visible.Card.Id] = new BattlefieldCardIdentity(
				visible.Card.Id, name, badge, displayLabel, $"{side} {displayLabel}",
				visible.IsLocal);
		}
	}

	public bool TryGet(int cardId, out BattlefieldCardIdentity? identity)
	{
		return visibleIdentities.TryGetValue(cardId, out identity);
	}

	private static string NormalizeName(string name)
	{
		return name.Trim().ToUpperInvariant();
	}

	private static string FormatBadge(int ordinal, bool isLocal)
	{
		return isLocal ? ordinal.ToString() : Letters(ordinal);
	}

	private static string Letters(int ordinal)
	{
		string value = string.Empty;
		while (ordinal > 0)
		{
			ordinal--;
			value = (char)('A' + ordinal % 26) + value;
			ordinal /= 26;
		}
		return value;
	}

	private sealed record Assignment(GroupKey Group, int Ordinal);
	private sealed record VisibleCard(int PlayerId, bool IsLocal, CardSnapshot Card);
	private readonly record struct GroupKey(int PlayerId, string NormalizedName);
}
