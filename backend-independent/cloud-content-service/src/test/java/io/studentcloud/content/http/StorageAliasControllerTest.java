package io.studentcloud.content.http;
import io.studentcloud.account.*;
import io.studentcloud.content.application.StorageAdministrationService;
import io.studentcloud.runtime.http.HttpFailures;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class StorageAliasControllerTest {
 private StorageAdministrationService service;private MockMvc mvc;
 private final Actor admin=new Actor(1,"root","root",null,Role.ADMIN,null);
 @BeforeEach void setup(){service=mock(StorageAdministrationService.class);mvc=MockMvcBuilders.standaloneSetup(new StorageAdministrationController(service,null)).setControllerAdvice(new HttpFailures()).build();}
 @Test void existingEditorStorageKeyQueryIsAccepted()throws Exception{
  mvc.perform(get("/admin/storage/exist/key").param("storageKey","fresh-drive").requestAttr(Actor.class.getName(),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
  verify(service).existsKey(admin,"fresh-drive");
 }
 @Test void canonicalKeyStillWorksAndReportsAnExistingAlias()throws Exception{
  when(service.existsKey(admin,"existing-drive")).thenReturn(true);
  mvc.perform(get("/admin/storage/exist/key").param("key","existing-drive").requestAttr(Actor.class.getName(),admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
 }
 @Test void contradictoryAliasesAreRejectedRatherThanSilentlyChoosingOne()throws Exception{
  mvc.perform(get("/admin/storage/exist/key").param("key","one-drive").param("storageKey","other-drive").requestAttr(Actor.class.getName(),admin)).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void missingAliasIsAValidationError()throws Exception{
  mvc.perform(get("/admin/storage/exist/key").requestAttr(Actor.class.getName(),admin)).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
}
