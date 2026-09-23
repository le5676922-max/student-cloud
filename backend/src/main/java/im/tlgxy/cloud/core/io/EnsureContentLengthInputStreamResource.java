/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.tlgxy.cloud.core.io;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;

/**
 * 确保内容长度的输入流资源封装类
 * <p>
 * 自定义的 {@link org.springframework.core.io.InputStreamResource} 子类，
 * 保证 {@link #contentLength()} 方法能够返回输入流的实际字节长度。
 * </p>
 * <p>
 * Spring 原生的 {@link org.springframework.core.io.InputStreamResource} 在
 * {@code contentLength()} 方法中总是抛出异常或返回 -1，因为 InputStream 本身
 * 无法确定总长度。而在 TlgxyCloud 的文件下载场景中，我们可以在创建流时就知道文件的
 * 实际大小，因此通过此类在构造时显式指定内容长度。
 * </p>
 * <p>
 * 此类相较于 {@link org.springframework.core.io.InputStreamResource} 仅重写了
 * {@link #contentLength()} 方法，使其返回构造时指定的准确长度值。
 * </p>
 *
 * <p>
 * {@link org.springframework.core.io.Resource} implementation for a given {@link InputStream}.
 * <p>Should only be used if no other specific {@code Resource} implementation
 * is applicable. In particular, prefer {@link ByteArrayResource} or any of the
 * file-based {@code Resource} implementations where possible.
 *
 * <p>In contrast to other {@code Resource} implementations, this is a descriptor
 * for an <i>already opened</i> resource - therefore returning {@code true} from
 * {@link #isOpen()}. Do not use an {@code InputStreamResource} if you need to
 * keep the resource descriptor somewhere, or if you need to read from a stream
 * multiple times.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 28.12.2003
 * @see ByteArrayResource
 * @see org.springframework.core.io.ClassPathResource
 * @see org.springframework.core.io.FileSystemResource
 * @see org.springframework.core.io.UrlResource
 */
public class EnsureContentLengthInputStreamResource extends InputStreamResource {

    /**
     * 输入流中数据的实际字节长度，在构造时指定
     */
    private final long contentLength;

    /**
     * 创建一个新的 InputStreamResource，并指定内容长度。
     *
     * @param inputStream   输入流
     * @param contentLength 输入流中数据的实际字节长度
     */
    public EnsureContentLengthInputStreamResource(InputStream inputStream, long contentLength) {
        super(inputStream);
        this.contentLength = contentLength;
    }

    /**
     * 返回输入流中数据的实际字节长度
     * <p>
     * 此方法覆盖了父类 {@link InputStreamResource#contentLength()} 的默认行为，
     * 返回构造时指定的精确长度值，用于 HTTP 响应头 {@code Content-Length} 的设置，
     * 使浏览器能够显示下载进度条和预估剩余时间。
     * </p>
     *
     * @return 输入流的字节长度
     */
    @Override
    public long contentLength() {
        return contentLength;
    }

}
