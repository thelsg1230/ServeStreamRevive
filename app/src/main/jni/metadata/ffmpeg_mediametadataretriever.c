/*
 * FFmpegMediaMetadataRetriever: A unified interface for retrieving frame 
 * and meta data from an input media file.
 *
 * Copyright 2016 William Seemann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/opt.h>
#include <ffmpeg_mediametadataretriever.h>
#include <ffmpeg_utils.h>

#include <stdio.h>
#include <unistd.h>

#include <android/log.h>

static int is_picture_codec(int codec_id) {
	return codec_id == AV_CODEC_ID_PNG || codec_id == AV_CODEC_ID_MJPEG || codec_id == AV_CODEC_ID_BMP;
}

int stream_component_open(State *s, int stream_index) {
	AVFormatContext *pFormatCtx = s->pFormatCtx;
	AVStream *stream;

	if (stream_index < 0 || stream_index >= pFormatCtx->nb_streams) {
		return FAILURE;
	}

	// Only stream information is needed for metadata, decoders are not opened.
	stream = pFormatCtx->streams[stream_index];

	switch (stream->codecpar->codec_type) {
		case AVMEDIA_TYPE_AUDIO:
			s->audio_stream = stream_index;
		    s->audio_st = stream;
			break;
		case AVMEDIA_TYPE_VIDEO:
			s->video_stream = stream_index;
		    s->video_st = stream;
			break;
		default:
			break;
	}

	return SUCCESS;
}

int set_data_source_l(State **ps, const char* path) {
	printf("set_data_source\n");
	int audio_index = -1;
	int video_index = -1;
	int i;

	State *state = *ps;
	
    printf("Path: %s\n", path);

    AVDictionary *options = NULL;
    av_dict_set(&options, "icy", "1", 0);
    av_dict_set(&options, "user-agent", "FFmpegMediaMetadataRetriever", 0);
    
    if (state->headers && state->headers[0]) {
        av_dict_set(&options, "headers", state->headers, 0);
    }
    
    if (state->offset > 0) {
        state->pFormatCtx = avformat_alloc_context();
        state->pFormatCtx->skip_initial_bytes = state->offset;
    }
    
    int open_ret = avformat_open_input(&state->pFormatCtx, path, NULL, &options);
    av_dict_free(&options);
    if (open_ret != 0) {
	    printf("Metadata could not be retrieved\n");
		*ps = NULL;
    	return FAILURE;
    }

	if (avformat_find_stream_info(state->pFormatCtx, NULL) < 0) {
	    printf("Metadata could not be retrieved\n");
	    avformat_close_input(&state->pFormatCtx);
		*ps = NULL;
    	return FAILURE;
	}

	set_duration(state->pFormatCtx);
	
	set_shoutcast_metadata(state->pFormatCtx);

	//av_dump_format(state->pFormatCtx, 0, path, 0);
	
    // Find the first audio and video stream
	for (i = 0; i < state->pFormatCtx->nb_streams; i++) {
		if (state->pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && video_index < 0) {
			video_index = i;
		}

		if (state->pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && audio_index < 0) {
			audio_index = i;
		}

		set_codec(state->pFormatCtx, i);
	}

	if (audio_index >= 0) {
		stream_component_open(state, audio_index);
	}

	if (video_index >= 0) {
		stream_component_open(state, video_index);
	}

	/*if(state->video_stream < 0 || state->audio_stream < 0) {
	    avformat_close_input(&state->pFormatCtx);
		*ps = NULL;
		return FAILURE;
	}*/

    set_rotation(state->pFormatCtx, state->audio_st, state->video_st);
    set_framerate(state->pFormatCtx, state->audio_st, state->video_st);
    set_filesize(state->pFormatCtx);
    set_chapter_count(state->pFormatCtx);
    set_video_dimensions(state->pFormatCtx, state->video_st);
    
	/*printf("Found metadata\n");
	AVDictionaryEntry *tag = NULL;
	while ((tag = av_dict_get(state->pFormatCtx->metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
    	printf("Key %s: \n", tag->key);
    	printf("Value %s: \n", tag->value);
    }*/
	
	*ps = state;
	return SUCCESS;
}

void init(State **ps) {
	State *state = *ps;

	if (state && state->pFormatCtx) {
		avformat_close_input(&state->pFormatCtx);
	}

	if (state && state->fd != -1) {
		close(state->fd);
	}
	
	if (!state) {
		state = av_mallocz(sizeof(State));
	}

	state->pFormatCtx = NULL;
	state->audio_stream = -1;
	state->video_stream = -1;
	state->audio_st = NULL;
	state->video_st = NULL;
	state->fd = -1;
	state->offset = 0;
	state->headers = NULL;

	*ps = state;
}

int set_data_source_uri(State **ps, const char* path, const char* headers) {
	State *state = *ps;
	
	ANativeWindow *native_window = NULL;

	if (state && state->native_window) {
		native_window = state->native_window;
	}

	init(&state);
	
	state->native_window = native_window;

	state->headers = headers;
	
	*ps = state;
	
	return set_data_source_l(ps, path);
}

int set_data_source_fd(State **ps, int fd, int64_t offset, int64_t length) {
    char path[256] = "";

	State *state = *ps;
	
	ANativeWindow *native_window = NULL;

	if (state && state->native_window) {
		native_window = state->native_window;
	}

	init(&state);

	state->native_window = native_window;
    	
    int myfd = dup(fd);

    char str[20];
    sprintf(str, "pipe:%d", myfd);
    strcat(path, str);
    
    state->fd = myfd;
    state->offset = offset;
    
	*ps = state;
    
    return set_data_source_l(ps, path);
}

const char* extract_metadata(State **ps, const char* key) {
	printf("extract_metadata\n");
    char* value = NULL;
	
	State *state = *ps;
    
	if (!state || !state->pFormatCtx) {
		return value;
	}

	return extract_metadata_internal(state->pFormatCtx, state->audio_st, state->video_st, key);
}

const char* extract_metadata_from_chapter(State **ps, const char* key, int chapter) {
	printf("extract_metadata_from_chapter\n");
    char* value = NULL;
	
	State *state = *ps;
	
	if (!state || !state->pFormatCtx || state->pFormatCtx->nb_chapters <= 0) {
		return value;
	}

	if (chapter < 0 || chapter >= state->pFormatCtx->nb_chapters) {
		return value;
	}

	return extract_metadata_from_chapter_internal(state->pFormatCtx, state->audio_st, state->video_st, key, chapter);
}

int get_metadata(State **ps, AVDictionary **metadata) {
    printf("get_metadata\n");
    
    State *state = *ps;
    
    if (!state || !state->pFormatCtx) {
        return FAILURE;
    }
    
    get_metadata_internal(state->pFormatCtx, metadata);
    
    return SUCCESS;
}

int get_embedded_picture(State **ps, AVPacket *pkt) {
	printf("get_embedded_picture\n");
	int i = 0;

	State *state = *ps;

	if (!state || !state->pFormatCtx || !pkt) {
		return FAILURE;
	}

    // find the first attached picture, if available
    for (i = 0; i < state->pFormatCtx->nb_streams; i++) {
        AVStream *st = state->pFormatCtx->streams[i];

        if ((st->disposition & AV_DISPOSITION_ATTACHED_PIC) && st->attached_pic.size > 0) {
        	printf("Found album art\n");

        	// Android can decode JPEG, PNG and BMP data directly.
        	if (!is_picture_codec(st->codecpar->codec_id)) {
        		continue;
        	}

        	av_packet_unref(pkt);

        	if (av_packet_ref(pkt, &st->attached_pic) < 0) {
        		return FAILURE;
        	}

        	return SUCCESS;
        }
    }

	return FAILURE;
}

// Video frame extraction is not supported (ServeStream only plays audio).
int get_frame_at_time(State **ps, int64_t timeUs, int option, AVPacket *pkt) {
	return FAILURE;
}

int get_scaled_frame_at_time(State **ps, int64_t timeUs, int option, AVPacket *pkt, int width, int height) {
	return FAILURE;
}

int set_native_window(State **ps, ANativeWindow* native_window) {
    printf("set_native_window\n");

	State *state = *ps;

	if (native_window == NULL) {
		return FAILURE;
	}

	if (!state) {
		init(&state);
	}

	state->native_window = native_window;

	*ps = state;

	return SUCCESS;
}

void release(State **ps) {
	printf("release\n");

	State *state = *ps;
	
    if (state) {
    	if (state->pFormatCtx) {
    		avformat_close_input(&state->pFormatCtx);
    	}
    	
    	if (state->fd != -1) {
    		close(state->fd);
    	}
    	
        // make sure we don't leak native windows
        if (state->native_window != NULL) {
            ANativeWindow_release(state->native_window);
            state->native_window = NULL;
        }

    	av_freep(&state);
        ps = NULL;
    }
}
