package io.studentcloud.drive.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadLimitsTest {
    @Test void configurationRejectsUnboundedFileSizesAndUnderfundedPublicationPeaks() {
        long gib=1024L*1024*1024;
        assertThrows(IllegalArgumentException.class,()->new UploadLimits(0,4*gib,8*gib));
        assertThrows(IllegalArgumentException.class,()->new UploadLimits(gib+1,8*gib,8*gib));
        assertThrows(IllegalArgumentException.class,()->new UploadLimits(gib,4*gib-1,8*gib));
        assertThrows(IllegalArgumentException.class,()->new UploadLimits(gib,4*gib,4*gib-1));
        var limits=new UploadLimits(gib,4*gib,8*gib);
        assertEquals(4*gib,limits.reservationBytes(gib));
        assertThrows(IllegalArgumentException.class,()->limits.reservationBytes(gib+1));
    }
    @Test void diskAdmissionCountsUnwrittenReservationsFromOtherAcceptedTasks() {
        long gib=1024L*1024*1024;
        var limits=new UploadLimits(gib,4*gib,8*gib);
        assertTrue(limits.canAdmitToDisk(9*gib/2,0,gib));
        assertFalse(limits.canAdmitToDisk(9*gib/2,4*gib,gib));
        assertTrue(limits.canAdmitToDisk(8*gib,4*gib,gib));
        assertFalse(limits.canAdmitToDisk(Long.MAX_VALUE,Long.MAX_VALUE,gib));
    }
    @Test void aSeparatePublicationVolumeCannotPromiseTheSameFreeBytesTwice() {
        long gib=1024L*1024*1024;
        var limits=new UploadLimits(gib,4*gib,8*gib);
        assertTrue(limits.canAdmitPublication(5*gib/2,0,gib));
        assertFalse(limits.canAdmitPublication(5*gib/2,4*gib,gib));
        assertTrue(limits.canAdmitPublication(4*gib,4*gib,gib));
        assertFalse(limits.canAdmitPublication(4*gib-1,4*gib,gib));
        assertFalse(limits.canAdmitPublication(4*gib,-1,gib));
    }
}
