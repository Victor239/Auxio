/*
 * Copyright (c) 2024 Auxio Project
 * taglib_write_jni.cpp is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>

#include "util.h"
#include "JStringRef.h"

#include "taglib/mpegfile.h"
#include "taglib/flacfile.h"
#include "taglib/opusfile.h"
#include "taglib/vorbisfile.h"
#include "taglib/mp4file.h"
#include "taglib/xiphcomment.h"
#include "taglib/popularimeterframe.h"
#include "taglib/textidentificationframe.h"
#include "taglib/id3v2tag.h"
#include "taglib/mp4item.h"

static void setPopmFrame(TagLib::ID3v2::Tag *tag, const TagLib::String &email,
        int ratingByte) {
    // Remove existing POPM frames with this email
    auto existingFrames = tag->frameList("POPM");
    for (auto frame : existingFrames) {
        if (auto popm = dynamic_cast<TagLib::ID3v2::PopularimeterFrame*>(frame)) {
            if (ratingByte == 0 || popm->email() == email) {
                tag->removeFrame(popm);
            }
        }
    }
    if (ratingByte > 0) {
        auto *frame = new TagLib::ID3v2::PopularimeterFrame();
        frame->setEmail(email);
        frame->setRating(ratingByte);
        frame->setCounter(0);
        tag->addFrame(frame);
    }
}

static void setFmpsRatingTxxx(TagLib::ID3v2::Tag *tag, int ratingByte) {
    // Remove any existing TXXX FMPS_Rating frame (case-insensitive description match)
    auto &frames = tag->frameList("TXXX");
    TagLib::ID3v2::FrameList toRemove;
    for (auto frame : frames) {
        if (auto txxx =
                dynamic_cast<TagLib::ID3v2::UserTextIdentificationFrame*>(frame)) {
            TagLib::String desc = txxx->description().upper();
            if (desc == "FMPS_RATING") {
                toRemove.append(frame);
            }
        }
    }
    for (auto frame : toRemove) {
        tag->removeFrame(frame);
    }
    if (ratingByte > 0) {
        std::ostringstream oss;
        oss << std::fixed << std::setprecision(8) << (ratingByte / 255.0);
        auto *frame = new TagLib::ID3v2::UserTextIdentificationFrame();
        frame->setDescription("FMPS_Rating");
        frame->setText(TagLib::String(oss.str()));
        tag->addFrame(frame);
    }
}

static void setXiphRating(TagLib::Ogg::XiphComment *tag, int ratingByte) {
    tag->removeFields("FMPS_RATING");
    if (ratingByte > 0) {
        std::ostringstream oss;
        oss << std::fixed << std::setprecision(8) << (ratingByte / 255.0);
        tag->addField("FMPS_RATING", TagLib::String(oss.str()));
    }
}

static void setMp4Rating(TagLib::MP4::Tag *tag, int ratingByte) {
    const char *key = "----:com.apple.iTunes:FMPS_Rating";
    tag->removeItem(key);
    if (ratingByte > 0) {
        std::ostringstream oss;
        oss << std::fixed << std::setprecision(8) << (ratingByte / 255.0);
        TagLib::StringList list;
        list.append(TagLib::String(oss.str()));
        tag->setItem(key, TagLib::MP4::Item(list));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_oxycblt_musikr_metadata_TagLibJNI_writeRatingNative(
        JNIEnv *env, jobject /* this */,
        jint fd, jstring mimeTypeJStr, jint ratingByte, jstring emailJStr) {
    std::string path = "/proc/self/fd/" + std::to_string(fd);

    JStringRef mimeTypeRef {env, mimeTypeJStr};
    JStringRef emailRef {env, emailJStr};
    std::string mimeType = mimeTypeRef.copy().to8Bit(true);
    TagLib::String email = emailRef.copy();

    try {
        if (mimeType == "audio/mpeg") {
            TagLib::MPEG::File file(path.c_str());
            if (!file.isValid()) {
                LOGE("writeRating: invalid MPEG file at fd %d", fd);
                return JNI_FALSE;
            }
            auto *tag = file.ID3v2Tag(true);
            setPopmFrame(tag, email, ratingByte);
            setFmpsRatingTxxx(tag, ratingByte);
            return file.save(TagLib::MPEG::File::ID3v2) ? JNI_TRUE : JNI_FALSE;
        } else if (mimeType == "audio/flac") {
            TagLib::FLAC::File file(path.c_str());
            if (!file.isValid()) {
                LOGE("writeRating: invalid FLAC file at fd %d", fd);
                return JNI_FALSE;
            }
            setXiphRating(file.xiphComment(true), ratingByte);
            return file.save() ? JNI_TRUE : JNI_FALSE;
        } else if (mimeType == "audio/vorbis" || mimeType == "audio/ogg") {
            TagLib::Ogg::Vorbis::File file(path.c_str());
            if (!file.isValid()) {
                LOGE("writeRating: invalid Vorbis file at fd %d", fd);
                return JNI_FALSE;
            }
            setXiphRating(file.tag(), ratingByte);
            return file.save() ? JNI_TRUE : JNI_FALSE;
        } else if (mimeType == "audio/opus") {
            TagLib::Ogg::Opus::File file(path.c_str());
            if (!file.isValid()) {
                LOGE("writeRating: invalid Opus file at fd %d", fd);
                return JNI_FALSE;
            }
            setXiphRating(file.tag(), ratingByte);
            return file.save() ? JNI_TRUE : JNI_FALSE;
        } else if (mimeType == "audio/aac" || mimeType == "audio/alac"
                || mimeType == "audio/mp4") {
            TagLib::MP4::File file(path.c_str());
            if (!file.isValid()) {
                LOGE("writeRating: invalid MP4 file at fd %d", fd);
                return JNI_FALSE;
            }
            setMp4Rating(file.tag(), ratingByte);
            return file.save() ? JNI_TRUE : JNI_FALSE;
        } else {
            LOGE("writeRating: unsupported MIME type %s", mimeType.c_str());
            return JNI_FALSE;
        }
    } catch (std::exception &e) {
        LOGE("writeRating: exception at fd %d: %s", fd, e.what());
        return JNI_FALSE;
    }
}
