/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2023 ViaVersion and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
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
package com.viaversion.viaversion.api.type.types.chunk;

import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.Environment;
import com.viaversion.viaversion.api.minecraft.chunks.BaseChunk;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.type.PartialType;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.version.Types1_13;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class ChunkType1_13 extends PartialType<Chunk, Environment> {

    private static final ChunkType1_13 WITH_SKYLIGHT = new ChunkType1_13(Environment.NORMAL);
    private static final ChunkType1_13 WITHOUT_SKYLIGHT = new ChunkType1_13(Environment.NETHER);

    public ChunkType1_13(Environment environment) {
        super(environment, Chunk.class);
    }

    public static ChunkType1_13 forEnvironment(Environment environment) {
        return environment == Environment.NORMAL ? WITH_SKYLIGHT : WITHOUT_SKYLIGHT;
    }

    @Override
    public Chunk read(ByteBuf input, Environment environment) throws Exception {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();

        boolean fullChunk = input.readBoolean();
        int primaryBitmask = Type.VAR_INT.readPrimitive(input);
        ByteBuf data = input.readSlice(Type.VAR_INT.readPrimitive(input));

        // Read sections
        ChunkSection[] sections = new ChunkSection[16];
        for (int i = 0; i < 16; i++) {
            if ((primaryBitmask & (1 << i)) == 0) continue; // Section not set

            ChunkSection section = Types1_13.CHUNK_SECTION.read(data);
            sections[i] = section;
            section.getLight().readBlockLight(data);
            if (environment == Environment.NORMAL) {
                section.getLight().readSkyLight(data);
            }
        }

        int[] biomeData = fullChunk ? new int[256] : null;
        if (fullChunk) {
            if (data.readableBytes() >= 256 * 4) {
                for (int i = 0; i < 256; i++) {
                    biomeData[i] = data.readInt();
                }
            } else {
                Via.getPlatform().getLogger().log(Level.WARNING, "Chunk x=" + chunkX + " z=" + chunkZ + " doesn't have biome data!");
            }
        }

        List<CompoundTag> nbtData = new ArrayList<>(Arrays.asList(Type.NAMED_COMPOUND_TAG_ARRAY.read(input)));

        // Read all the remaining bytes (workaround for #681)
        if (input.readableBytes() > 0) {
            byte[] array = Type.REMAINING_BYTES.read(input);
            if (Via.getManager().isDebug()) {
                Via.getPlatform().getLogger().warning("Found " + array.length + " more bytes than expected while reading the chunk: " + chunkX + "/" + chunkZ);
            }
        }

        return new BaseChunk(chunkX, chunkZ, fullChunk, false, primaryBitmask, sections, biomeData, nbtData);
    }

    @Override
    public void write(ByteBuf output, Environment environment, Chunk chunk) throws Exception {
        output.writeInt(chunk.getX());
        output.writeInt(chunk.getZ());

        output.writeBoolean(chunk.isFullChunk());
        Type.VAR_INT.writePrimitive(output, chunk.getBitmask());

        ByteBuf buf = output.alloc().buffer();
        try {
            for (int i = 0; i < 16; i++) {
                ChunkSection section = chunk.getSections()[i];
                if (section == null) continue; // Section not set
                Types1_13.CHUNK_SECTION.write(buf, section);
                section.getLight().writeBlockLight(buf);

                if (!section.getLight().hasSkyLight()) continue; // No sky light, we're done here.
                section.getLight().writeSkyLight(buf);

            }
            buf.readerIndex(0);
            Type.VAR_INT.writePrimitive(output, buf.readableBytes() + (chunk.isBiomeData() ? 1024 : 0));
            output.writeBytes(buf);
        } finally {
            buf.release(); // release buffer
        }

        // Write biome data
        if (chunk.isBiomeData()) {
            for (int value : chunk.getBiomeData()) {
                output.writeInt(value);
            }
        }

        // Write Block Entities
        Type.NAMED_COMPOUND_TAG_ARRAY.write(output, chunk.getBlockEntities().toArray(new CompoundTag[0]));
    }
}
