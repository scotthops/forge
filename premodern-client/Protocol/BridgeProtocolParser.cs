#nullable enable

using System;
using System.Collections.Generic;
using System.Text.Json;

namespace PremodernClient.Protocol;

public static class BridgeProtocolParser
{
    public static BridgeMessage Parse(string line)
    {
        try
        {
            using JsonDocument document = JsonDocument.Parse(line);
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return Problem("INVALID_JSON_OBJECT", "A JSONL line must contain one object.", line);
            }

            if (!TryInt32(root, "schemaVersion", out int schemaVersion))
            {
                return Problem("MISSING_SCHEMA_VERSION", "Message has no integer schemaVersion.", line);
            }

            if (schemaVersion != BridgeSchema.SupportedVersion)
            {
                return Problem("UNSUPPORTED_SCHEMA_VERSION",
                    $"Received schemaVersion {schemaVersion}; supported version is {BridgeSchema.SupportedVersion}.",
                    line);
            }

            string? type = String(root, "type");
            if (string.IsNullOrWhiteSpace(type))
            {
                return Problem("MISSING_MESSAGE_TYPE", "Message has no string type.", line);
            }

            return type switch
            {
                "lifecycle" => new LifecycleMessage(schemaVersion,
                    String(root, "event") ?? "unknown", String(root, "detail")),
                "controller" => new ControllerMessage(schemaVersion,
                    NullableInt32(root, "playerId"), String(root, "playerName"),
                    String(root, "controllerInterface"), String(root, "implementation")),
                "state" => ParseState(schemaVersion, root),
                "interaction" => ParseInteraction(schemaVersion, root),
                "query" => ParseQuery(schemaVersion, root),
                "error" => ParseError(schemaVersion, root),
                "actionAccepted" => new ActionAcceptedMessage(schemaVersion,
                    String(root, "action"), NullableInt32(root, "selectedId"),
                    Int64(root, "interactionSequence")),
                _ => new UnknownBridgeMessage(schemaVersion, type, line)
            };
        }
        catch (JsonException exception)
        {
            return Problem("INVALID_JSON", exception.Message, line);
        }
        catch (Exception exception)
        {
            return Problem("MESSAGE_PARSE_FAILED", exception.Message, line);
        }
    }

    private static StateMessage ParseState(int schemaVersion, JsonElement root)
    {
        List<PlayerSnapshot> players = [];
        foreach (JsonElement player in Array(root, "players"))
        {
            players.Add(new PlayerSnapshot(
                Int32(player, "id"),
                String(player, "name"),
                Int32(player, "life"),
                Boolean(player, "hasPriority"),
                ParseCards(player, "handVisible"),
                ParseCards(player, "battlefield"),
                ParseCards(player, "graveyard")));
        }

        List<StackSnapshot> stack = [];
        foreach (JsonElement item in Array(root, "stack"))
        {
            CardSnapshot? source = null;
            if (item.TryGetProperty("source", out JsonElement sourceElement)
                && sourceElement.ValueKind == JsonValueKind.Object)
            {
                source = ParseCard(sourceElement);
            }
            stack.Add(new StackSnapshot(
                Int32(item, "id"),
                String(item, "text"),
                source,
                ParseCards(item, "targets")));
        }

        return new StateMessage(
            schemaVersion,
            String(root, "source"),
            Int64(root, "stateSequence", -1),
            Int32(root, "turn"),
            String(root, "phase"),
            NullableInt32(root, "activePlayerId"),
            NullableInt32(root, "priorityPlayerId"),
            players,
            stack);
    }

    private static InteractionMessage ParseInteraction(int schemaVersion, JsonElement root)
    {
        ButtonSnapshot buttons = new(null, null, false, false, false);
        if (root.TryGetProperty("buttons", out JsonElement buttonElement)
            && buttonElement.ValueKind == JsonValueKind.Object)
        {
            buttons = new ButtonSnapshot(
                String(buttonElement, "okLabel"),
                String(buttonElement, "cancelLabel"),
                Boolean(buttonElement, "okEnabled"),
                Boolean(buttonElement, "cancelEnabled"),
                Boolean(buttonElement, "focusOk"));
        }

        return new InteractionMessage(
            schemaVersion,
            Int64(root, "interactionSequence"),
            String(root, "reason"),
            String(root, "prompt"),
            ParseIds(root, "weaklySelectableCardIds"),
            ParseIds(root, "selectableCardIds"),
            ParseIds(root, "selectablePlayerIds"),
            Int32(root, "min"),
            Int32(root, "max"),
            buttons);
    }

    private static QueryMessage ParseQuery(int schemaVersion, JsonElement root)
    {
        List<QueryChoice> choices = [];
        foreach (JsonElement choice in Array(root, "choices"))
        {
            choices.Add(new QueryChoice(
                Int32(choice, "id"),
                String(choice, "description"),
                Boolean(choice, "canPlay")));
        }
        return new QueryMessage(
            schemaVersion,
            String(root, "requestId"),
            String(root, "kind"),
            NullableInt32(root, "hostCardId"),
            String(root, "hostCardName"),
            choices,
            String(root, "offered"),
            NullableBoolean(root, "cancellable"));
    }

    private static ErrorMessage ParseError(int schemaVersion, JsonElement root)
    {
        return new ErrorMessage(
            schemaVersion,
            String(root, "code"),
            String(root, "message"),
            String(root, "requestId"),
            NullableInt64(root, "receivedInteractionSequence"),
            NullableInt64(root, "currentInteractionSequence"),
            NullableInt64(root, "receivedSchemaVersion"),
            NullableInt32(root, "supportedSchemaVersion"));
    }

    private static IReadOnlyList<CardSnapshot> ParseCards(JsonElement parent, string property)
    {
        List<CardSnapshot> cards = [];
        foreach (JsonElement card in Array(parent, property))
        {
            cards.Add(ParseCard(card));
        }
        return cards;
    }

    private static CardSnapshot ParseCard(JsonElement card)
    {
        return new CardSnapshot(
            Int32(card, "id"),
            String(card, "name"),
            String(card, "zone"),
            Boolean(card, "hidden"),
            Boolean(card, "tapped"));
    }

    private static IReadOnlyList<int> ParseIds(JsonElement parent, string property)
    {
        List<int> ids = [];
        foreach (JsonElement element in Array(parent, property))
        {
            if (element.TryGetInt32(out int id))
            {
                ids.Add(id);
            }
        }
        return ids;
    }

    private static IEnumerable<JsonElement> Array(JsonElement parent, string property)
    {
        if (parent.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.Array)
        {
            return value.EnumerateArray();
        }
        return [];
    }

    private static string? String(JsonElement parent, string property)
    {
        return parent.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static bool Boolean(JsonElement parent, string property, bool fallback = false)
    {
        return parent.TryGetProperty(property, out JsonElement value)
            && (value.ValueKind == JsonValueKind.True || value.ValueKind == JsonValueKind.False)
            ? value.GetBoolean() : fallback;
    }

    private static bool? NullableBoolean(JsonElement parent, string property)
    {
        return parent.TryGetProperty(property, out JsonElement value)
            && (value.ValueKind == JsonValueKind.True || value.ValueKind == JsonValueKind.False)
            ? value.GetBoolean() : null;
    }

    private static int Int32(JsonElement parent, string property, int fallback = 0)
    {
        return TryInt32(parent, property, out int value) ? value : fallback;
    }

    private static bool TryInt32(JsonElement parent, string property, out int result)
    {
        result = 0;
        return parent.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.Number
            && value.TryGetInt32(out result);
    }

    private static int? NullableInt32(JsonElement parent, string property)
    {
        return TryInt32(parent, property, out int value) ? value : null;
    }

    private static long Int64(JsonElement parent, string property, long fallback = 0)
    {
        return NullableInt64(parent, property) ?? fallback;
    }

    private static long? NullableInt64(JsonElement parent, string property)
    {
        return parent.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.Number
            && value.TryGetInt64(out long result) ? result : null;
    }

    private static ProtocolProblemMessage Problem(string code, string detail, string rawJson)
    {
        return new ProtocolProblemMessage(code, detail, rawJson);
    }
}
