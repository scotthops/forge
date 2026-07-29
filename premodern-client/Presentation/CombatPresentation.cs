#nullable enable

using PremodernClient.Protocol;
using System.Collections.Generic;
using System.Linq;

namespace PremodernClient.Presentation;

public static class CombatPresentation
{
	public static string CardRelationshipText(int cardId, StateMessage? state,
		BattlefieldCardIdentityMap identities)
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
				attacking.BlockerCardIds.Select(identities.QualifiedLabel)));
		}

		List<int> blockedAttackers = state.Combat
			.Where(assignment => assignment.BlockerCardIds.Contains(cardId))
			.Select(assignment => assignment.AttackerCardId)
			.ToList();
		if (blockedAttackers.Count > 0)
		{
			relationships.Add("BLOCKING:\n" + string.Join(", ",
				blockedAttackers.Select(identities.QualifiedLabel)));
		}
		return string.Join("\n", relationships);
	}

	public static IReadOnlyList<string> AssignmentSummaries(StateMessage state,
		BattlefieldCardIdentityMap identities)
	{
		return state.Combat
			.SelectMany(combat => combat.BlockerCardIds.Select(blockerId =>
				$"{identities.QualifiedLabel(blockerId)} blocks "
				+ identities.QualifiedLabel(combat.AttackerCardId)))
			.ToList();
	}

	public static string QualifiedLabel(this BattlefieldCardIdentityMap identities, int cardId)
	{
		return identities.TryGet(cardId, out BattlefieldCardIdentity? identity)
			&& identity != null
			? identity.QualifiedLabel
			: $"Visible battlefield card #{cardId}";
	}
}
