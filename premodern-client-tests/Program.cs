#nullable enable

using PremodernClient.Presentation;
using PremodernClient.Protocol;
using System;
using System.Collections.Generic;
using System.Text.Json;

internal static class Program
{
	private static int assertions;

	private static void Main()
	{
		DuplicateCardsHaveDistinctCombatLabels();
		LabelsRemainStableAsBattlefieldsChange();
		HiddenAndUniqueCardsDoNotGainBadges();
		GenericChoiceProtocolIsCorrelated();
		RevealPersistsIndependentlyOfState();
		Console.WriteLine($"Presentation identity tests passed ({assertions} assertions).");
	}

	private static void GenericChoiceProtocolIsCorrelated()
	{
		const string json = "{\"schemaVersion\":1,\"type\":\"query\",\"requestId\":\"q-9\","
			+ "\"kind\":\"genericChoice\",\"prompt\":\"Choose a card name\",\"min\":1,\"max\":1,"
			+ "\"options\":[{\"optionId\":\"option-7\",\"label\":\"Mountain\",\"selected\":false}]}";
		QueryMessage query = (QueryMessage)BridgeProtocolParser.Parse(json);
		Equal("q-9", query.RequestId);
		Equal("genericChoice", query.Kind);
		Equal(1, query.Min);
		Equal(1, query.Max);
		Equal("option-7", query.Options[0].OptionId);
		Equal("Mountain", query.Options[0].Label);

		BridgeClientState state = new();
		state.Apply(query);
		state.Apply(new ErrorMessage(1, "invalidGenericChoice", "bad ID", "q-9",
			null, null, null, null));
		True(state.PendingQuery?.RequestId == "q-9",
			"A rejected generic reply must leave the query pending.");
		GenericChoiceReplyCommand reply = new("q-9", ["option-7"]);
		using JsonDocument command = JsonDocument.Parse(BridgeCommandSerializer.Serialize(reply));
		Equal("q-9", command.RootElement.GetProperty("requestId").GetString());
		Equal("option-7", command.RootElement.GetProperty("selectedOptionIds")[0].GetString());
		state.RecordCommandSent(reply);
		Equal<QueryMessage?>(null, state.PendingQuery);
	}

	private static void RevealPersistsIndependentlyOfState()
	{
		const string json = "{\"schemaVersion\":1,\"type\":\"reveal\","
			+ "\"message\":\"Revealed card\",\"items\":[{\"optionId\":\"reveal-1\","
			+ "\"label\":\"Mountain\",\"cardId\":42}]}";
		RevealMessage reveal = (RevealMessage)BridgeProtocolParser.Parse(json);
		BridgeClientState client = new();
		client.Apply(reveal);
		client.Apply(State([], [], []));
		Equal("Mountain", client.LatestReveal?.Items[0].Label);
		Equal(42, client.LatestReveal?.Items[0].CardId);
	}

	private static void DuplicateCardsHaveDistinctCombatLabels()
	{
		StateMessage state = State(
			localBattlefield:
			[
				Card(101, "Jackal Pup"),
				Card(102, "Jackal Pup"),
				Card(103, "Jackal Pup")
			],
			opponentBattlefield:
			[
				Card(201, "Jackal Pup"),
				Card(202, "Jackal Pup"),
				Card(203, "Jackal Pup")
			],
			combat:
			[
				new CombatSnapshot(201, [101]),
				new CombatSnapshot(202, [102]),
				new CombatSnapshot(203, [103])
			]);
		BattlefieldCardIdentityMap identities = new();
		identities.Update(state, 1);

		Equal("1", Identity(identities, 101).Badge);
		Equal("2", Identity(identities, 102).Badge);
		Equal("3", Identity(identities, 103).Badge);
		Equal("A", Identity(identities, 201).Badge);
		Equal("B", Identity(identities, 202).Badge);
		Equal("C", Identity(identities, 203).Badge);

		IReadOnlyList<string> assignments = CombatPresentation.AssignmentSummaries(
			state, identities);
		Equal("Your Jackal Pup 1 blocks Opponent Jackal Pup A", assignments[0]);
		Equal("Your Jackal Pup 2 blocks Opponent Jackal Pup B", assignments[1]);
		Equal("Your Jackal Pup 3 blocks Opponent Jackal Pup C", assignments[2]);
		Equal("BLOCKING:\nOpponent Jackal Pup A",
			CombatPresentation.CardRelationshipText(101, state, identities));
		Equal("BLOCKED BY:\nYour Jackal Pup 1",
			CombatPresentation.CardRelationshipText(201, state, identities));
	}

	private static void LabelsRemainStableAsBattlefieldsChange()
	{
		BattlefieldCardIdentityMap identities = new();
		identities.Update(State([Card(101, "Jackal Pup")], [], []), 1);
		Equal(null, Identity(identities, 101).Badge);

		identities.Update(State(
			[Card(101, "Jackal Pup"), Card(102, "Jackal Pup")], [], []), 1);
		Equal("1", Identity(identities, 101).Badge);
		Equal("2", Identity(identities, 102).Badge);

		identities.Update(State([Card(102, "Jackal Pup")], [], []), 1);
		Equal(null, Identity(identities, 102).Badge);

		identities.Update(State(
			[Card(102, "Jackal Pup"), Card(103, "Jackal Pup")], [], []), 1);
		Equal("2", Identity(identities, 102).Badge);
		Equal("3", Identity(identities, 103).Badge);

		identities.Update(State(
			[Card(101, "Jackal Pup"), Card(102, "Jackal Pup"), Card(103, "Jackal Pup")],
			[], []), 1);
		Equal("1", Identity(identities, 101).Badge);
		Equal("2", Identity(identities, 102).Badge);
		Equal("3", Identity(identities, 103).Badge);
	}

	private static void HiddenAndUniqueCardsDoNotGainBadges()
	{
		CardSnapshot hidden = new(999, "Hidden identity", "Battlefield", true, false, false);
		StateMessage state = State(
			[Card(101, "Mogg Fanatic"), hidden],
			[Card(201, "Jackal Pup")],
			[]);
		BattlefieldCardIdentityMap identities = new();
		identities.Update(state, 1);

		Equal(null, Identity(identities, 101).Badge);
		Equal(null, Identity(identities, 201).Badge);
		True(!identities.TryGet(999, out _), "Hidden battlefield cards must not get identities.");
	}

	private static BattlefieldCardIdentity Identity(
		BattlefieldCardIdentityMap identities, int cardId)
	{
		True(identities.TryGet(cardId, out BattlefieldCardIdentity? identity)
			&& identity != null, $"Expected visible identity for card {cardId}.");
		return identity!;
	}

	private static StateMessage State(IReadOnlyList<CardSnapshot> localBattlefield,
		IReadOnlyList<CardSnapshot> opponentBattlefield,
		IReadOnlyList<CombatSnapshot> combat)
	{
		return new StateMessage(
			1, "test", 1, 1, "COMBAT_DECLARE_BLOCKERS", 2, 1,
			[
				new PlayerSnapshot(1, "Local", 20, true, 0, [], localBattlefield, []),
				new PlayerSnapshot(2, "Opponent", 20, false, 0, [], opponentBattlefield, [])
			],
			[],
			combat);
	}

	private static CardSnapshot Card(int id, string name)
	{
		return new CardSnapshot(id, name, "Battlefield", false, false, false);
	}

	private static void Equal<T>(T expected, T actual)
	{
		assertions++;
		if (!EqualityComparer<T>.Default.Equals(expected, actual))
		{
			throw new InvalidOperationException($"Expected '{expected}', got '{actual}'.");
		}
	}

	private static void True(bool condition, string message)
	{
		assertions++;
		if (!condition)
		{
			throw new InvalidOperationException(message);
		}
	}
}
