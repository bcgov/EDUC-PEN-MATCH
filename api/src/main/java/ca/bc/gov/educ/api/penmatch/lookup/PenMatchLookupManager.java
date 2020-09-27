package ca.bc.gov.educ.api.penmatch.lookup;

import ca.bc.gov.educ.api.penmatch.filter.FilterOperation;
import ca.bc.gov.educ.api.penmatch.model.*;
import ca.bc.gov.educ.api.penmatch.properties.ApplicationProperties;
import ca.bc.gov.educ.api.penmatch.repository.ForeignSurnameRepository;
import ca.bc.gov.educ.api.penmatch.repository.MatchCodesRepository;
import ca.bc.gov.educ.api.penmatch.repository.NicknamesRepository;
import ca.bc.gov.educ.api.penmatch.repository.SurnameFrequencyRepository;
import ca.bc.gov.educ.api.penmatch.rest.RestPageImpl;
import ca.bc.gov.educ.api.penmatch.rest.RestUtils;
import ca.bc.gov.educ.api.penmatch.struct.Search;
import ca.bc.gov.educ.api.penmatch.struct.SearchCriteria;
import ca.bc.gov.educ.api.penmatch.struct.ValueType;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMasterRecord;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMatchNames;
import ca.bc.gov.educ.api.penmatch.util.PenMatchUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static ca.bc.gov.educ.api.penmatch.struct.Condition.AND;
import static ca.bc.gov.educ.api.penmatch.struct.Condition.OR;

@Service
@Slf4j
public class PenMatchLookupManager {

    private static final DateTimeFormatter DOB_FORMATTER_SHORT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DOB_FORMATTER_LONG = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PARAMETERS_ATTRIBUTE = "parameters";
    public static final String CHECK_DIGIT_ERROR_CODE_000 = "000";
    public static final String CHECK_DIGIT_ERROR_CODE_001 = "001";
    public static final Integer VERY_FREQUENT = 500;
    public static final Integer NOT_VERY_FREQUENT = 50;
    public static final Integer VERY_RARE = 5;
    private Map<String, String> matchCodesMap;

    @Getter(AccessLevel.PRIVATE)
    private final ForeignSurnameRepository foreignSurnameRepository;

    @Getter(AccessLevel.PRIVATE)
    private final SurnameFrequencyRepository surnameFrequencyRepository;

    @Getter(AccessLevel.PRIVATE)
    private final NicknamesRepository nicknamesRepository;

    @Getter(AccessLevel.PRIVATE)
    private final MatchCodesRepository matchCodesRepository;


    private final RestUtils restUtils;

    private final ApplicationProperties props;

    @Autowired
    public PenMatchLookupManager(final ForeignSurnameRepository foreignSurnameRepository, final NicknamesRepository nicknamesRepository, final SurnameFrequencyRepository surnameFrequencyRepository, final MatchCodesRepository matchCodesRepository, final RestUtils restUtils, final ApplicationProperties props) {
        this.foreignSurnameRepository = foreignSurnameRepository;
        this.nicknamesRepository = nicknamesRepository;
        this.surnameFrequencyRepository = surnameFrequencyRepository;
        this.matchCodesRepository = matchCodesRepository;
        this.restUtils = restUtils;
        this.props = props;
    }

    /**
     * Local ID is not blank, lookup with all parts
     *
     */
    public List<StudentEntity> lookupWithAllParts(String dob, String surname, String givenName, String mincode, String localID) {
        try {
            RestTemplate restTemplate = restUtils.getRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            LocalDate dobDate = LocalDate.parse(dob, DOB_FORMATTER_SHORT);

            SearchCriteria criteriaDob = SearchCriteria.builder().key("dob").operation(FilterOperation.EQUAL).value(DOB_FORMATTER_LONG.format(dobDate)).valueType(ValueType.DATE).build();
            List<SearchCriteria> criteriaListDob = new LinkedList<>();
            criteriaListDob.add(criteriaDob);

            SearchCriteria criteriaSurname = SearchCriteria.builder().key("legalLastName").operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(surname).valueType(ValueType.STRING).build();
            SearchCriteria criteriaGiven = SearchCriteria.builder().key("legalFirstName").condition(AND).operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(givenName).valueType(ValueType.STRING).build();

            List<SearchCriteria> criteriaListSurnameGiven = new LinkedList<>();
            criteriaListSurnameGiven.add(criteriaSurname);
            criteriaListSurnameGiven.add(criteriaGiven);

            SearchCriteria criteriaMincode = SearchCriteria.builder().key("mincode").operation(FilterOperation.EQUAL).value(mincode).valueType(ValueType.STRING).build();
            SearchCriteria criteriaLocalID = SearchCriteria.builder().key("localID").condition(AND).operation(FilterOperation.EQUAL).value(localID).valueType(ValueType.STRING).build();
            List<SearchCriteria> criteriaListMincodeLocalID = new LinkedList<>();
            criteriaListMincodeLocalID.add(criteriaMincode);
            criteriaListMincodeLocalID.add(criteriaLocalID);

            List<Search> searches = new LinkedList<>();
            searches.add(Search.builder().searchCriteriaList(criteriaListDob).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListSurnameGiven).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListMincodeLocalID).build());

            ObjectMapper objectMapper = new ObjectMapper();
            String criteriaJSON = objectMapper.writeValueAsString(searches);

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getStudentApiURL() + "/paginated").queryParam("searchCriteriaList", criteriaJSON).queryParam("pageSize", 100000);

            DefaultUriBuilderFactory defaultUriBuilderFactory = new DefaultUriBuilderFactory();
            defaultUriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
            restTemplate.setUriTemplateHandler(defaultUriBuilderFactory);

            ParameterizedTypeReference<RestPageImpl<StudentEntity>> responseType = new ParameterizedTypeReference<RestPageImpl<StudentEntity>>() { };

            ResponseEntity<RestPageImpl<StudentEntity>> studentResponse = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, responseType);

            if(studentResponse.hasBody()) {
                return studentResponse.getBody().getContent();
            }
            return new ArrayList<>();
        } catch (JsonProcessingException e) {
            log.error("Error occurred while writing criteria as JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Looking using local ID but don't use initial
     */
    public List<StudentEntity> lookupNoInit(String dob, String surname, String mincode, String localID) {
        try {
            RestTemplate restTemplate = restUtils.getRestTemplate();

            LocalDate dobDate = LocalDate.parse(dob, DOB_FORMATTER_SHORT);

            SearchCriteria criteriaDob = SearchCriteria.builder().key("dob").operation(FilterOperation.EQUAL).value(DOB_FORMATTER_LONG.format(dobDate)).valueType(ValueType.DATE).build();
            List<SearchCriteria> criteriaListDob = new LinkedList<>();
            criteriaListDob.add(criteriaDob);

            SearchCriteria criteriaSurname = SearchCriteria.builder().key("legalLastName").operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(surname).valueType(ValueType.STRING).build();

            List<SearchCriteria> criteriaListSurname = new LinkedList<>();
            criteriaListSurname.add(criteriaSurname);

            SearchCriteria criteriaMincode = SearchCriteria.builder().key("mincode").operation(FilterOperation.EQUAL).value(mincode).valueType(ValueType.STRING).build();
            SearchCriteria criteriaLocalID = SearchCriteria.builder().key("localID").condition(AND).operation(FilterOperation.EQUAL).value(localID).valueType(ValueType.STRING).build();
            List<SearchCriteria> criteriaListMincodeLocalID = new LinkedList<>();
            criteriaListMincodeLocalID.add(criteriaMincode);
            criteriaListMincodeLocalID.add(criteriaLocalID);

            List<Search> searches = new LinkedList<>();
            searches.add(Search.builder().searchCriteriaList(criteriaListDob).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListSurname).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListMincodeLocalID).build());

            ObjectMapper objectMapper = new ObjectMapper();
            String criteriaJSON = objectMapper.writeValueAsString(searches);

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getStudentApiURL() + "/paginated").queryParam("searchCriteriaList", criteriaJSON).queryParam("pageSize", 100000);

            DefaultUriBuilderFactory defaultUriBuilderFactory = new DefaultUriBuilderFactory();
            defaultUriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
            restTemplate.setUriTemplateHandler(defaultUriBuilderFactory);

            ParameterizedTypeReference<RestPageImpl<StudentEntity>> responseType = new ParameterizedTypeReference<RestPageImpl<StudentEntity>>() { };

            ResponseEntity<RestPageImpl<StudentEntity>> studentResponse = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, responseType);

            if(studentResponse.hasBody()) {
                return studentResponse.getBody().getContent();
            }
            return new ArrayList<>();

        } catch (JsonProcessingException e) {
            log.error("Error occurred while writing criteria as JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Perform lookup with no local ID
     */
    public List<StudentEntity> lookupNoLocalID(String dob, String surname, String givenName) {
        try {
            RestTemplate restTemplate = restUtils.getRestTemplate();

            LocalDate dobDate = LocalDate.parse(dob, DOB_FORMATTER_SHORT);

            SearchCriteria criteriaDob = SearchCriteria.builder().key("dob").operation(FilterOperation.EQUAL).value(DOB_FORMATTER_LONG.format(dobDate)).valueType(ValueType.DATE).build();
            List<SearchCriteria> criteriaListDob = new LinkedList<>();
            criteriaListDob.add(criteriaDob);

            SearchCriteria criteriaSurname = SearchCriteria.builder().key("legalLastName").operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(surname).valueType(ValueType.STRING).build();
            SearchCriteria criteriaGiven = SearchCriteria.builder().key("legalFirstName").condition(AND).operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(givenName).valueType(ValueType.STRING).build();
            List<SearchCriteria> criteriaListSurnameGiven = new LinkedList<>();
            criteriaListSurnameGiven.add(criteriaSurname);
            criteriaListSurnameGiven.add(criteriaGiven);

            List<Search> searches = new LinkedList<>();
            searches.add(Search.builder().searchCriteriaList(criteriaListDob).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListSurnameGiven).build());

            ObjectMapper objectMapper = new ObjectMapper();
            String criteriaJSON = objectMapper.writeValueAsString(searches);

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getStudentApiURL() + "/paginated").queryParam("searchCriteriaList", criteriaJSON).queryParam("pageSize", 100000);

            DefaultUriBuilderFactory defaultUriBuilderFactory = new DefaultUriBuilderFactory();
            defaultUriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
            restTemplate.setUriTemplateHandler(defaultUriBuilderFactory);

            ParameterizedTypeReference<RestPageImpl<StudentEntity>> responseType = new ParameterizedTypeReference<RestPageImpl<StudentEntity>>() { };

            ResponseEntity<RestPageImpl<StudentEntity>> studentResponse = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, responseType);

            if(studentResponse.hasBody()) {
                return studentResponse.getBody().getContent();
            }
            return new ArrayList<>();

        } catch (JsonProcessingException e) {
            log.error("Error occurred while writing criteria as JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lookup with no initial or local ID
     */
    public List<StudentEntity> lookupNoInitNoLocalID(String dob, String surname) {
        try {
            RestTemplate restTemplate = restUtils.getRestTemplate();

            LocalDate dobDate = LocalDate.parse(dob, DOB_FORMATTER_SHORT);

            SearchCriteria criteriaDob = SearchCriteria.builder().key("dob").operation(FilterOperation.EQUAL).value(DOB_FORMATTER_LONG.format(dobDate)).valueType(ValueType.DATE).build();
            List<SearchCriteria> criteriaListDob = new LinkedList<>();
            criteriaListDob.add(criteriaDob);

            SearchCriteria criteriaSurname = SearchCriteria.builder().key("legalLastName").operation(FilterOperation.STARTS_WITH_IGNORE_CASE).value(surname).valueType(ValueType.STRING).build();
            List<SearchCriteria> criteriaListSurname = new LinkedList<>();
            criteriaListSurname.add(criteriaSurname);
            List<Search> searches = new LinkedList<>();
            searches.add(Search.builder().searchCriteriaList(criteriaListDob).build());
            searches.add(Search.builder().condition(OR).searchCriteriaList(criteriaListSurname).build());

            ObjectMapper objectMapper = new ObjectMapper();
            String criteriaJSON = objectMapper.writeValueAsString(searches);

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getStudentApiURL() + "/paginated").queryParam("searchCriteriaList", criteriaJSON).queryParam("pageSize", 100000);

            DefaultUriBuilderFactory defaultUriBuilderFactory = new DefaultUriBuilderFactory();
            defaultUriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
            restTemplate.setUriTemplateHandler(defaultUriBuilderFactory);

            ParameterizedTypeReference<RestPageImpl<StudentEntity>> responseType = new ParameterizedTypeReference<RestPageImpl<StudentEntity>>() { };

            ResponseEntity<RestPageImpl<StudentEntity>> studentResponse = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, responseType);

            if(studentResponse.hasBody()) {
                return studentResponse.getBody().getContent();
            }
            return new ArrayList<>();
        } catch (JsonProcessingException e) {
            log.error("Error occurred while writing criteria as JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fetches a PEN Master Record given a student number
     */
    public PenMasterRecord lookupStudentByPEN(String pen) {
        if (pen != null) {
            RestTemplate restTemplate = restUtils.getRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            ParameterizedTypeReference<List<StudentEntity>> type = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<List<StudentEntity>> studentResponse = restTemplate.exchange(props.getStudentApiURL() + "?pen=" + pen, HttpMethod.GET, new HttpEntity<>(PARAMETERS_ATTRIBUTE, headers), type);

            if (studentResponse.hasBody() && !studentResponse.getBody().isEmpty()) {
                return PenMatchUtils.convertStudentEntityToPenMasterRecord(studentResponse.getBody().get(0));
            }
        }
        return null;
    }

    /**
     * Fetches a PEN Master Record given a student number
     */
    public String lookupStudentTruePENNumberByStudentID(String studentID) {
        if (studentID != null) {
            RestTemplate restTemplate = restUtils.getRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            ParameterizedTypeReference<List<StudentMergeEntity>> type = new ParameterizedTypeReference<>() {
            };
            ResponseEntity<List<StudentMergeEntity>> studentResponse = restTemplate.exchange(props.getStudentApiURL() + "/" + studentID + "/merges?mergeDirection=TO", HttpMethod.GET, new HttpEntity<>(PARAMETERS_ATTRIBUTE, headers), type);

            if (studentResponse.hasBody()) {
                return studentResponse.getBody().get(0).getMergeStudent().getPen().trim();
            }
        }
        return null;
    }

    /**
     * Fetches the student's merge to record
     */
    public PenMasterRecord lookupStudentMergeToRecord(String studentNumber) {
        if (studentNumber != null) {
            RestTemplate restTemplate = restUtils.getRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            ResponseEntity<StudentEntity> studentResponse;
            studentResponse = restTemplate.exchange(props.getStudentApiURL() + "?pen=" + studentNumber, HttpMethod.GET, new HttpEntity<>(PARAMETERS_ATTRIBUTE, headers), StudentEntity.class);

            if (studentResponse.hasBody()) {
                return PenMatchUtils.convertStudentEntityToPenMasterRecord(studentResponse.getBody());
            }
        }
        return null;
    }

    /**
     * Look up nicknames Nickname1 (by convention) is the "base" nickname. For
     * example, we would expect the following in the nickname file:
     * <p>
     * Nickname 1 Nickname 2 JAMES JIM JAMES JIMMY JAMES JAIMIE
     */
    public List<NicknamesEntity> lookupNicknamesOnly(String givenName) {
        if (givenName == null || givenName.length() < 1) {
            return new ArrayList<>();
        }

        String givenNameUpper = givenName.toUpperCase();

        return getNicknamesRepository().findAllByNickname1OrNickname2(givenNameUpper, givenNameUpper);
    }

    /**
     * Look up nicknames Nickname1 (by convention) is the "base" nickname. For
     * example, we would expect the following in the nickname file:
     * <p>
     * Nickname 1 Nickname 2 JAMES JIM JAMES JIMMY JAMES JAIMIE
     */
    public void lookupNicknames(PenMatchNames penMatchTransactionNames, String givenName) {
        if (givenName == null || givenName.length() < 1) {
            return;
        }

        String givenNameUpper = givenName.toUpperCase();

        // Part 1 - Find the base nickname
        String baseNickname = null;

        List<NicknamesEntity> nicknamesBaseList = getNicknamesRepository().findAllByNickname1OrNickname2(givenNameUpper, givenNameUpper);
        if (nicknamesBaseList != null && !nicknamesBaseList.isEmpty()) {
            baseNickname = nicknamesBaseList.get(0).getNickname1().trim();
        }

        // Part 2 - Base nickname has been found; now find all the nickname2's,
        // bypassing the one that is the same as the given name in the transaction.
        // The base nickname should be stored as well if it is not the same as the given
        // name
        if (baseNickname != null) {
            if (!baseNickname.equals(givenNameUpper)) {
                penMatchTransactionNames.setNickname1(baseNickname);
            }

            List<NicknamesEntity> tempNicknamesList = getNicknamesRepository().findAllByNickname1OrNickname2(baseNickname, baseNickname);

            for (NicknamesEntity nickEntity : tempNicknamesList) {
                if (!nickEntity.getNickname2().equals(givenNameUpper)) {
                    PenMatchUtils.setNextNickname(penMatchTransactionNames, nickEntity.getNickname2().trim());
                }

                if (penMatchTransactionNames.getNickname4() != null && !penMatchTransactionNames.getNickname4().isEmpty()) {
                    break;
                }
            }
        }
    }


    /**
     * Check frequency of surname
     */
    public Integer lookupSurnameFrequency(String fullStudentSurname) {
        if (fullStudentSurname == null) {
            return 0;
        }
        // Note this returns in two different places
        Integer surnameFrequency = 0;
        List<SurnameFrequencyEntity> surnameFreqEntityList = getSurnameFrequencyRepository().findAllBySurnameStartingWith(fullStudentSurname);

        for (SurnameFrequencyEntity surnameFreqEntity : surnameFreqEntityList) {
            surnameFrequency = surnameFrequency + Integer.valueOf(surnameFreqEntity.getSurnameFrequency());

            if (surnameFrequency >= VERY_FREQUENT) {
                break;
            }
        }

        return surnameFrequency;
    }

    /**
     * Lookup foreign surname
     */
    public boolean lookupForeignSurname(String surname, String ancestry) {
        LocalDate curDate = LocalDate.now();

        Optional<ForeignSurnamesEntity> foreignSurnamesEntities = getForeignSurnameRepository().findBySurnameAndAncestryAndEffectiveDateLessThanEqualAndExpiryDateGreaterThanEqual(surname, ancestry, curDate, curDate);

        return foreignSurnamesEntities.isPresent();
    }

    /**
     * Lookup match codes
     */
    public String lookupMatchResult(String matchCode) {
        if (matchCode == null) {
            return null;
        }

        if(matchCodesMap == null){
            matchCodesMap = new ConcurrentHashMap<>();
            List<MatchCodesEntity> matchCodesEntities = getMatchCodesRepository().findAll();
            for(MatchCodesEntity entity: matchCodesEntities){
                matchCodesMap.put(entity.getMatchCode(), entity.getMatchResult());
            }
        }

        if (matchCodesMap.containsKey(matchCode)) {
            return matchCodesMap.get(matchCode);
        }

        return matchCode;
    }

    //Evict cache every 24 hours and reload again
    @Scheduled(fixedRate = 86400000)
    public void evictAllMatchCodesCache() {
      log.info("Evicting match codes cache");
      if (matchCodesMap != null) {
        matchCodesMap.clear();
      }
      matchCodesMap = getMatchCodesRepository().findAll().stream().collect(Collectors.toConcurrentMap(MatchCodesEntity::getMatchCode, MatchCodesEntity::getMatchResult));
      log.info("Reloaded match codes into cache. {} entries", matchCodesMap.size());
    }

    @PostConstruct
    public void init(){
      log.info("Load Match codes during startup.");
      matchCodesMap = getMatchCodesRepository().findAll().stream().collect(Collectors.toConcurrentMap(MatchCodesEntity::getMatchCode, MatchCodesEntity::getMatchResult));
      log.info("Loaded Match codes during startup. {} entries", matchCodesMap.size());
    }

}
