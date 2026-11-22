import type {Hospital} from '../api';

export const HOME_HOSPITAL_FILTERS = {query:'서울대학교병원',region:'서울특별시',department:'05',exactName:'true'};

export type BookingDraft = {doctorId:string;date:string;slotId?:string};
export type HomeRecommendation = {hospital:Hospital;department?:string};
export type HomeProps = {
  onBooking:(hospital:Hospital,department?:string,draft?:BookingDraft)=>void;
  onAccount:()=>void;
  recommendation?:HomeRecommendation;
  initialScene?:string;
};
