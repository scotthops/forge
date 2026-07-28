#nullable enable

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

namespace PremodernClient.Protocol;

public abstract record BridgeCommand(string Type);

public abstract record AsyncInteractionCommand(string Type, long InteractionSequence)
    : BridgeCommand(Type);

public sealed record SelectCardCommand(int CardId, long InteractionSequence)
    : AsyncInteractionCommand("selectCard", InteractionSequence);

public sealed record SelectPlayerCommand(int PlayerId, long InteractionSequence)
    : AsyncInteractionCommand("selectPlayer", InteractionSequence);

public enum BridgeButton
{
    Ok,
    Cancel
}

public sealed record ButtonCommand(BridgeButton Button, long InteractionSequence)
    : AsyncInteractionCommand("button", InteractionSequence);

public sealed record PassPriorityCommand(long InteractionSequence)
    : AsyncInteractionCommand("passPriority", InteractionSequence);

public sealed record ReplyCommand(string RequestId, int SelectedId)
    : BridgeCommand("reply");

public sealed record CombatDamageAmount(string RecipientKey, int Amount);

public sealed record CombatDamageReplyCommand(
    string RequestId,
    IReadOnlyList<CombatDamageAmount> Assignments,
    bool Skip = false)
    : BridgeCommand("reply");

public sealed record ItemOrderingReplyCommand(
    string RequestId,
    IReadOnlyList<string> OrderedItemIds,
    bool RememberDecision = false)
    : BridgeCommand("reply");

public static class BridgeCommandSerializer
{
    public static string Serialize(BridgeCommand command)
    {
        ArgumentNullException.ThrowIfNull(command);
        object envelope = command switch
        {
            SelectCardCommand selectCard => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = selectCard.Type,
                cardId = selectCard.CardId,
                interactionSequence = selectCard.InteractionSequence
            },
            SelectPlayerCommand selectPlayer => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = selectPlayer.Type,
                playerId = selectPlayer.PlayerId,
                interactionSequence = selectPlayer.InteractionSequence
            },
            ButtonCommand button => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = button.Type,
                button = button.Button == BridgeButton.Ok ? "ok" : "cancel",
                interactionSequence = button.InteractionSequence
            },
            PassPriorityCommand passPriority => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = passPriority.Type,
                interactionSequence = passPriority.InteractionSequence
            },
            ReplyCommand reply when !string.IsNullOrWhiteSpace(reply.RequestId) => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = reply.Type,
                requestId = reply.RequestId,
                selectedId = reply.SelectedId
            },
            ReplyCommand => throw new ArgumentException("A reply command requires a requestId.", nameof(command)),
            CombatDamageReplyCommand reply when !string.IsNullOrWhiteSpace(reply.RequestId) => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = reply.Type,
                requestId = reply.RequestId,
                assignments = reply.Assignments.Select(assignment => new
                {
                    recipientKey = assignment.RecipientKey,
                    amount = assignment.Amount
                }),
                skip = reply.Skip
            },
            CombatDamageReplyCommand => throw new ArgumentException(
                "A combat damage reply command requires a requestId.", nameof(command)),
            ItemOrderingReplyCommand reply when !string.IsNullOrWhiteSpace(reply.RequestId) => new
            {
                schemaVersion = BridgeSchema.SupportedVersion,
                type = reply.Type,
                requestId = reply.RequestId,
                orderedItemIds = reply.OrderedItemIds,
                rememberDecision = reply.RememberDecision
            },
            ItemOrderingReplyCommand => throw new ArgumentException(
                "An item ordering reply command requires a requestId.", nameof(command)),
            _ => throw new ArgumentException($"Unsupported bridge command type: {command.GetType().Name}",
                nameof(command))
        };
        return JsonSerializer.Serialize(envelope);
    }
}
