/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016-2017:
 * 	Una Thompson (unascribed),
 * 	Isaac Ellingson (Falkreon),
 * 	Jamie Mansfield (jamierocks),
 * 	and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.elytradev.concrete.resgen;

import com.elytradev.concrete.common.ConcreteLog;
import com.elytradev.concrete.common.ShadingValidator;
import com.elytradev.concrete.reflect.accessor.Accessor;
import com.elytradev.concrete.reflect.accessor.Accessors;
import com.elytradev.concrete.reflect.invoker.Invoker;
import com.elytradev.concrete.reflect.invoker.Invokers;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.LegacyV2Adapter;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.registries.IRegistryDelegate;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom resource pack that is used when there's missing assets in your assets folder,
 * allows for auto genning common models like cubes and sprite based items.
 *
 * @see IResourceHolder for custom asset locations for items and blocks.
 * @see ResourceProvider for custom asset types, and methods to provide those assets.
 */
public class ConcreteResourcePack extends AbstractResourcePack {

	static {
		ShadingValidator.ensureShaded();
	}

	private static Accessor<List<IResourcePack>> resourcePackList = Accessors.findField(FMLClientHandler.class, "resourcePackList");

	private static Accessor<File> resourcePackFile = Accessors.findField(AbstractResourcePack.class, "field_110597_b", "resourcePackFile");
	private static Accessor<IResourcePack> legacyPack = Accessors.findField(LegacyV2Adapter.class, "field_191383_a", "pack");
	private static Accessor<Map<String, FallbackResourceManager>> domainResourceManagers = Accessors.findField(SimpleReloadableResourceManager.class, "field_110548_a", "domainResourceManagers");
	private static Accessor<List<IResourcePack>> resourcePacks = Accessors.findField(FallbackResourceManager.class, "field_110540_a", "resourcePacks");

	private static Invoker hasResourceName = Invokers.findMethod(AbstractResourcePack.class,"hasResourceName", "func_110593_b", String.class);
	private static Invoker getInputStreamByName = Invokers.findMethod(AbstractResourcePack.class, "getInputStreamByName", "func_110591_a", String.class);

	public AbstractResourcePack realResourcePack;
	public String modID;
	public List<ResourceProvider> providers;

	/**
	 * Create a ConcreteResourcePack for the specified mod, will auto gen simplemodels if you lack a blockstate or model file.
	 * If the applicable file exists it will simply default to it.
	 *
	 * @param modID the id of the mod you want a simple pack for.
	 */
	public ConcreteResourcePack(String modID) {
		super(getPackFileByModID(modID));
		this.modID = modID;
		this.providers = Lists.newArrayList(new BlockStateResourceProvider(this),
				new BlockModelResourceProvider(this),
				new ItemModelResourceProvider(this));

		// Obtain the real resource pack generated by forge.
		IResourcePack realPack = FMLClientHandler.instance().getResourcePackFor(modID);
		if (realPack instanceof LegacyV2Adapter) {
			this.realResourcePack = (AbstractResourcePack) legacyPack.get(realPack);
		} else if (realPack instanceof AbstractResourcePack) {
			this.realResourcePack = (AbstractResourcePack) realPack;
		}

		if (realPack == null || this.realResourcePack == null) {
			throw new MissingRealpackException(modID);
		}

		//Add our pack as a default pack, use FMLClientHandler's field for this so we don't need to worry about obf names
		resourcePackList.get(FMLClientHandler.instance()).add(resourcePackList.get(FMLClientHandler.instance()).indexOf(realPack), this);

		// Confirms that our resourcepack is available as soon as possible to prevent missing resource errors.
		if (Minecraft.getMinecraft().getResourceManager() instanceof SimpleReloadableResourceManager) {
			// Forces this resource pack to be loaded early on. FML already did it's initial registration so we need to bypass that.
			FallbackResourceManager domainManager = domainResourceManagers.get(Minecraft.getMinecraft().getResourceManager()).get(modID);
			resourcePacks.get(domainManager).add(resourcePacks.get(domainManager).indexOf(realPack), this);
		}
	}

	private static File getPackFileByModID(String modID) {
		IResourcePack pack = FMLClientHandler.instance().getResourcePackFor(modID);
		if (pack instanceof LegacyV2Adapter) {
			return resourcePackFile.get(legacyPack.get(pack));
		} else if (pack instanceof AbstractResourcePack) {
			return resourcePackFile.get(pack);
		}
		return null;
	}

	/**
	 * Converts a name given by AbstractResourcePack back into a ResourceLocation.
	 *
	 * @param name provided by AbstractResourcePack
	 * @return a ResourceLocation matching the given data.
	 */
	public static ResourceLocation nameToLocation(String name) {
		name = name.substring(name.indexOf("/") + 1);
		String domain = name.substring(0, name.indexOf("/"));
		String path = name.substring(name.indexOf("/") + 1);

		ConcreteLog.debug("Converted {} to {}", name, new ResourceLocation(domain, path));
		return new ResourceLocation(domain, path);
	}

	/**
	 * Used in place of an Accessor due to an error with static fields.
	 *
	 * @return the value of ModelLoader.customModels
	 */
	public static Map<Pair<IRegistryDelegate<Item>, Integer>, ModelResourceLocation> getCustomModels() {
		try {
			Field field = ModelLoader.class.getDeclaredField("customModels");
			return (Map<Pair<IRegistryDelegate<Item>, Integer>, ModelResourceLocation>) FieldUtils.readStaticField(field, true);
		} catch (Exception e) {
			ConcreteLog.error("Caught exception getting customModels from the model loader, ", e);
		}

		return Collections.emptyMap();
	}

	@Override
	public InputStream getInputStreamByName(String name) throws IOException {
		// Return a stream corresponding to a matching location.
		ConcreteLog.debug("ConcreteResourcePack was asked to obtain: {}", name);
		for (int i = 0; i < providers.size(); i++) {
			if (providers.get(i).canProvide(name)) {
				return providers.get(i).provide(name);
			}
		}

		// Use the real pack in the event that we're asked for a resource we don't have.
		// Most notable example being pack.mcmeta.
		return (InputStream) getInputStreamByName.invoke(realResourcePack, name);
	}

	@Override
	public boolean hasResourceName(String name) {
		for (ResourceProvider provider : providers) {
			if (provider.canProvide(name))
				return true;
		}

		return false;
	}

	@Override
	public Set<String> getResourceDomains() {
		return Collections.singleton(modID);
	}

	/**
	 * Gets an item based on the name given by AbstractResourcePack.
	 *
	 * @param name the name provided by AbstractResourcePack
	 * @return the Item corresponding to the given name.
	 */
	public Item getItem(String name) {
		String itemID = name.substring(name.lastIndexOf("/") + 1, name.lastIndexOf("."));
		if (Item.getByNameOrId(modID + ":" + itemID) != null) {
			return Item.getByNameOrId(modID + ":" + itemID);
		} else {
			ResourceLocation location = nameToLocation(name);
			try {
				HashBiMap<Pair<IRegistryDelegate<Item>, Integer>, ModelResourceLocation> customModelsMap = HashBiMap.create(getCustomModels());
				String resourcePath = location.getResourcePath();
				resourcePath = resourcePath.substring(resourcePath.lastIndexOf("/") + 1);
				resourcePath = resourcePath.substring(0, resourcePath.lastIndexOf("."));
				String domain = location.getResourceDomain();
				location = new ModelResourceLocation(domain + ":" + resourcePath, isLocation(name, "/models/item/") ? "inventory" : "normal");
				if (customModelsMap.inverse().containsKey(location))
					return customModelsMap.inverse().get(location).getLeft().get();
			} catch (Exception e) {
				ConcreteLog.error("Failed to get item from ResourceLocation", e);
			}
			return Items.AIR;
		}
	}

	/**
	 * Attempt to get metadata value from ModelLoader to account for custom mesh locations, returns 0 if none were found.
	 *
	 * @param name name provided by getInputStreamByName
	 * @return metadata if found, 0 if none.
	 */
	public Integer getMetaFromName(String name) {
		ResourceLocation location = nameToLocation(name);
		try {
			HashBiMap<Pair<IRegistryDelegate<Item>, Integer>, ModelResourceLocation> customModelsMap = HashBiMap.create(getCustomModels());
			String resourcePath = location.getResourcePath();
			resourcePath = resourcePath.substring(resourcePath.lastIndexOf("/") + 1);
			resourcePath = resourcePath.substring(0, resourcePath.lastIndexOf("."));
			String domain = location.getResourceDomain();
			location = new ModelResourceLocation(domain + ":" + resourcePath, isLocation(name, "/models/item/") ? "inventory" : "normal");
			if (customModelsMap.inverse().containsKey(location))
				return customModelsMap.inverse().get(location).getRight();
		} catch (Exception e) {
			ConcreteLog.error("Failed to get metadata from name", e);
		}
		return 0;
	}

	/**
	 * Wrapper for hasResourceName invoker, used to give resource providers access if needed.
	 *
	 * @param name the resource name.
	 * @return true if the realpack has the specified resource, false otherwise.
	 */
	public boolean realpackHasResourceName(String name) {
		return (boolean) hasResourceName.invoke(realResourcePack, name);
	}

	/**
	 * Wrapper for getInputStreamByName, used to give resource providers access if needed.
	 *
	 * @param name the resource name.
	 * @return the realpack input stream for the specified resource.
	 */
	public InputStream getRealpackInputStreamByName(String name) {
		return (InputStream) getInputStreamByName.invoke(realResourcePack, name);
	}

	/**
	 * Check if the place provided matches the location validation.
	 *
	 * @param place      The place to check.
	 * @param validation The validation to use.
	 * @return true if matches, false otherwise.
	 */
	private boolean isLocation(String place, String validation) {
		return place.startsWith("assets/" + modID + validation) && place.endsWith(".json");
	}

}
