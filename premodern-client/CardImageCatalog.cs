#nullable enable

using Godot;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace PremodernClient;

public sealed class CardImageCatalog
{
	private readonly Dictionary<string, Texture2D> textures = new(StringComparer.OrdinalIgnoreCase);

	public CardImageCatalog(string imageDirectory)
	{
		foreach (string fileName in DirAccess.GetFilesAt(imageDirectory))
		{
			if (!string.Equals(Path.GetExtension(fileName), ".webp", StringComparison.OrdinalIgnoreCase))
			{
				continue;
			}

			string key = Path.GetFileNameWithoutExtension(fileName);
			string resourcePath = $"{imageDirectory.TrimEnd('/')}/{fileName}";
			Texture2D? texture = ResourceLoader.Load<Texture2D>(resourcePath);
			if (texture == null)
			{
				GD.PushWarning($"Could not load card preview image: {resourcePath}");
				continue;
			}

			textures[key] = texture;
		}
	}

	public bool TryGetTexture(string? cardName, out Texture2D texture)
	{
		if (!string.IsNullOrWhiteSpace(cardName)
			&& textures.TryGetValue(NormalizeName(cardName), out Texture2D? found))
		{
			texture = found;
			return true;
		}

		texture = null!;
		return false;
	}

	private static string NormalizeName(string cardName)
	{
		StringBuilder normalized = new();
		bool needsSeparator = false;
		foreach (char character in cardName.Normalize(NormalizationForm.FormD))
		{
			if (CharUnicodeInfo.GetUnicodeCategory(character) == UnicodeCategory.NonSpacingMark)
			{
				continue;
			}

			if (char.IsLetterOrDigit(character))
			{
				if (needsSeparator && normalized.Length > 0)
				{
					normalized.Append('-');
				}
				normalized.Append(char.ToLowerInvariant(character));
				needsSeparator = false;
			}
			else
			{
				needsSeparator = true;
			}
		}
		return normalized.ToString();
	}
}
